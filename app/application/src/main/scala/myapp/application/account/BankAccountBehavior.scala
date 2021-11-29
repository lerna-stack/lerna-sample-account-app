package myapp.application.account

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import com.fasterxml.jackson.annotation.{ JsonSubTypes, JsonTypeInfo }
import com.fasterxml.jackson.databind.annotation.{ JsonDeserialize, JsonSerialize }
import com.fasterxml.jackson.databind.util.StdConverter
import lerna.akka.entityreplication.typed._
import lerna.log.{ AppLogger, AppTypedActorLogging }
import lerna.util.lang.Equals._
import myapp.adapter.account.{ AccountNo, TransactionId }
import myapp.utility.AppRequestContext
import myapp.utility.tenant.AppTenant

import java.time.ZonedDateTime
import scala.collection.immutable.ListMap
import scala.concurrent.duration._

object BankAccountBehavior extends AppTypedActorLogging {

  /** 残高の上限値 */
  val BalanceMaxLimit: BigInt = 10_000_000

  def typeKey(implicit tenant: AppTenant): ReplicatedEntityTypeKey[Command] =
    ReplicatedEntityTypeKey(s"BankAccount-${tenant.id}")

  sealed trait Command
  final case class Deposit(transactionId: TransactionId, amount: BigInt, replyTo: ActorRef[DepositReply])(implicit
      val appRequestContext: AppRequestContext,
  ) extends Command
  final case class Withdraw(transactionId: TransactionId, amount: BigInt, replyTo: ActorRef[WithdrawReply])(implicit
      val appRequestContext: AppRequestContext,
  ) extends Command
  final case class Refund(
      transactionId: TransactionId,
      withdrawalTransactionId: TransactionId,
      amount: BigInt,
      replyTo: ActorRef[RefundReply],
  )(implicit
      val appRequestContext: AppRequestContext,
  ) extends Command

  final case class GetBalance(replyTo: ActorRef[AccountBalance])(implicit val appRequestContext: AppRequestContext)
      extends Command
  final case class ReceiveTimeout() extends Command
  final case class Stop()           extends Command
  sealed trait Reply
  sealed trait DepositReply                           extends Reply
  final case class DepositSucceeded(balance: BigInt)  extends DepositReply
  final case class ExcessBalance()                    extends DepositReply
  sealed trait WithdrawReply                          extends Reply
  final case class ShortBalance()                     extends WithdrawReply
  final case class WithdrawSucceeded(balance: BigInt) extends WithdrawReply
  sealed trait RefundReply                            extends Reply
  final case class RefundSucceeded(balance: BigInt)   extends RefundReply
  final case class InvalidRefundCommand()             extends RefundReply

  // GetBalanceReply
  final case class AccountBalance(balance: BigInt) extends Reply

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
  @JsonSubTypes(
    Array(
      new JsonSubTypes.Type(name = "Deposited", value = classOf[Deposited]),
      new JsonSubTypes.Type(name = "BalanceExceeded", value = classOf[BalanceExceeded]),
      new JsonSubTypes.Type(name = "Withdrew", value = classOf[Withdrew]),
      new JsonSubTypes.Type(name = "BalanceShorted", value = classOf[BalanceShorted]),
      new JsonSubTypes.Type(name = "Refunded", value = classOf[Refunded]),
      new JsonSubTypes.Type(name = "InvalidRefundRequested", value = classOf[InvalidRefundRequested]),
    ),
  )
  sealed trait DomainEvent {
    def appRequestContext: AppRequestContext
  }

  sealed trait DepositDomainEvent extends DomainEvent
  final case class Deposited(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: BigInt,
      balance: Int,
      transactedAt: Long,
  )(implicit
      val appRequestContext: AppRequestContext,
  ) extends DepositDomainEvent
  final case class BalanceExceeded(transactionId: TransactionId)(implicit
      val appRequestContext: AppRequestContext,
  ) extends DepositDomainEvent

  sealed trait WithdrawalDomainEvent extends DomainEvent
  final case class Withdrew(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: BigInt,
      balance: Int,
      transactedAt: Long,
  )(implicit
      val appRequestContext: AppRequestContext,
  ) extends WithdrawalDomainEvent
  final case class BalanceShorted(transactionId: TransactionId)(implicit
      val appRequestContext: AppRequestContext,
  ) extends WithdrawalDomainEvent

  sealed trait RefundDomainEvent extends DomainEvent
  final case class Refunded(
      accountNo: AccountNo,
      transactionId: TransactionId,
      withdrawalTransactionId: TransactionId,
      amount: BigInt,
      balance: Int,
      transactedAt: Long,
  )(implicit val appRequestContext: AppRequestContext)
      extends RefundDomainEvent
  final case class InvalidRefundRequested(
      transactionId: TransactionId,
      withdrawalTransactionId: TransactionId,
      amount: BigInt,
  )(implicit
      val appRequestContext: AppRequestContext,
  ) extends RefundDomainEvent

  type Effect = lerna.akka.entityreplication.typed.Effect[DomainEvent, Account]

  final case class Account(
      accountNo: AccountNo,
      balance: BigInt,
      @JsonSerialize(converter = classOf[Account.ResentTransactionsSerializerConverter])
      @JsonDeserialize(converter = classOf[Account.ResentTransactionsDeserializerConverter])
      resentTransactions: ListMap[TransactionId, DomainEvent],
  ) {

    def deposit(amount: BigInt): Account =
      copy(balance = balance + amount)

    def withdraw(amount: BigInt): Account =
      copy(balance = balance - amount)

    def refund(amount: BigInt): Account =
      copy(balance = balance + amount)

    private[this] val maxResentTransactionSize = 30

    def recordEvent(transactionId: TransactionId, event: DomainEvent): Account =
      copy(resentTransactions = (resentTransactions + (transactionId -> event)).takeRight(maxResentTransactionSize))

    @SuppressWarnings(Array("lerna.warts.CyclomaticComplexity"))
    def applyCommand(command: Command, logger: AppLogger): Effect =
      command match {
        case command @ Deposit(transactionId, amount, replyTo) =>
          import command.appRequestContext
          resentTransactions.get(transactionId) match {
            // Receive a known transaction: replies message based on the stored event in recentTransactions
            case Some(_: Deposited) =>
              Effect.reply(replyTo)(DepositSucceeded(balance))
            case Some(_: BalanceExceeded) =>
              Effect.reply(replyTo)(ExcessBalance())
            case Some(_: WithdrawalDomainEvent) | Some(_: RefundDomainEvent) =>
              Effect.unhandled.thenNoReply()
            // Receive an unknown transaction
            case None =>
              if (balance + amount > BalanceMaxLimit) {
                val event = BalanceExceeded(transactionId)
                Effect
                  .replicate[DomainEvent, Account](event)
                  .thenRun(logEvent(event, logger)(_))
                  .thenReply(replyTo)(_ => ExcessBalance())
              } else {
                val event = Deposited(
                  accountNo,
                  transactionId,
                  amount,
                  (amount + balance).toInt,
                  ZonedDateTime.now().toEpochSecond,
                )
                Effect
                  .replicate[DomainEvent, Account](event)
                  .thenRun(logEvent(event, logger)(_))
                  .thenReply(replyTo)(state => DepositSucceeded(state.balance))
              }
          }
        case command @ Withdraw(transactionId, amount, replyTo) =>
          import command.appRequestContext
          resentTransactions.get(transactionId) match {
            // Receive a known transaction: replies message based on stored event in resetTransactions
            case Some(_: Withdrew) =>
              Effect.reply(replyTo)(WithdrawSucceeded(balance))
            case Some(_: BalanceShorted) =>
              Effect.reply(replyTo)(ShortBalance())
            case Some(_: DepositDomainEvent) | Some(_: RefundDomainEvent) =>
              Effect.unhandled.thenNoReply()
            // Receive an unknown transaction
            case None =>
              if (balance < amount) {
                val event = BalanceShorted(transactionId)
                Effect
                  .replicate[DomainEvent, Account](event)
                  .thenRun(logEvent(event, logger)(_))
                  .thenReply(replyTo)(_ => ShortBalance())
              } else {
                val event = Withdrew(
                  accountNo,
                  transactionId,
                  amount,
                  (balance - amount).toInt,
                  ZonedDateTime.now().toEpochSecond,
                )
                Effect
                  .replicate[DomainEvent, Account](event)
                  .thenRun(logEvent(event, logger)(_))
                  .thenReply(replyTo)(state => WithdrawSucceeded(state.balance))
              }
          }
        case command: Refund =>
          applyRefundCommand(command, logger)
        case GetBalance(replyTo) =>
          Effect.reply(replyTo)(AccountBalance(balance))
        case ReceiveTimeout() =>
          Effect.passivate().thenNoReply()
        case Stop() =>
          Effect.stopLocally()
      }

    private def applyRefundCommand(command: Refund, logger: AppLogger): Effect = {
      import command.appRequestContext
      val Refund(transactionId, withdrawalTransactionId, refundAmount, replyTo) = command
      resentTransactions.get(transactionId) match {
        case Some(refunded: Refunded) =>
          val isValidCommand = {
            refunded.withdrawalTransactionId === withdrawalTransactionId &&
            refunded.amount === refundAmount
          }
          if (isValidCommand) {
            Effect.unhandled
              .thenRun { _: Account =>
                logger.info(
                  "[{}] The refund for {} is already succeeded",
                  transactionId,
                  withdrawalTransactionId,
                )
              }
              .thenReply(replyTo)(_ => RefundSucceeded(balance))
          } else {
            Effect.unhandled
              .thenRun { _: Account =>
                logger.info(
                  "[{}] The command has different parameters({},{}) than previous command's parameters",
                  transactionId,
                  withdrawalTransactionId,
                  refundAmount,
                )
              }
              .thenReply(replyTo)(_ => InvalidRefundCommand())
          }
        case Some(_: InvalidRefundRequested) | Some(_: DepositDomainEvent) | Some(_: WithdrawalDomainEvent) =>
          Effect.reply(replyTo)(InvalidRefundCommand())
        case None =>
          if (refundAmount <= 0) {
            val event = InvalidRefundRequested(transactionId, withdrawalTransactionId, refundAmount)
            Effect
              .replicate[DomainEvent, Account](event)
              .thenRun(logEvent(event, logger)(_))
              .thenReply(replyTo)(_ => InvalidRefundCommand())
          } else {
            val event = Refunded(
              accountNo,
              transactionId,
              withdrawalTransactionId,
              refundAmount,
              (balance + refundAmount).toInt,
              ZonedDateTime.now().toEpochSecond,
            )
            Effect
              .replicate[DomainEvent, Account](event)
              .thenRun(logEvent(event, logger)(_))
              .thenReply(replyTo)(state => RefundSucceeded(state.balance))
          }
      }
    }

    def applyEvent(event: DomainEvent): Account =
      event match {
        case Deposited(_, transactionId, amount, _, _)   => deposit(amount).recordEvent(transactionId, event)
        case BalanceExceeded(transactionId)              => recordEvent(transactionId, event)
        case Withdrew(_, transactionId, amount, _, _)    => withdraw(amount).recordEvent(transactionId, event)
        case BalanceShorted(transactionId)               => recordEvent(transactionId, event)
        case Refunded(_, transactionId, _, amount, _, _) => refund(amount).recordEvent(transactionId, event)
        case InvalidRefundRequested(transactionId, _, _) => recordEvent(transactionId, event)
      }

    private[this] def logEvent(event: DomainEvent, logger: AppLogger)(
        state: Account,
    )(implicit appRequestContext: AppRequestContext): Unit = {
      val ANSI_YELLOW = "\u001B[33m"
      val ANSI_RESET  = "\u001B[0m"
      logger.info(
        s"${ANSI_YELLOW}[LEADER]${ANSI_RESET} ${event.toString} [balance: ${state.balance.toString}, resent-transactions: ${state.resentTransactions.size.toString}]",
      )
    }
  }

  object Account {

    private[BankAccountBehavior] class ResentTransactionsSerializerConverter
        extends StdConverter[ListMap[TransactionId, DomainEvent], List[(TransactionId, DomainEvent)]] {
      override def convert(value: ListMap[TransactionId, DomainEvent]): List[(TransactionId, DomainEvent)] =
        value.toList
    }

    private[BankAccountBehavior] class ResentTransactionsDeserializerConverter
        extends StdConverter[List[(TransactionId, DomainEvent)], ListMap[TransactionId, DomainEvent]] {
      override def convert(value: List[(TransactionId, DomainEvent)]): ListMap[TransactionId, DomainEvent] =
        ListMap.from(value)
    }
  }

  def apply(entityContext: ReplicatedEntityContext[Command]): Behavior[Command] = withLogger { logger =>
    Behaviors.setup { context =>
      // This is highly recommended to identify the source of log outputs
      context.setLoggerName(BankAccountBehavior.getClass)
      // ReceiveTimeout will trigger Effect.passivate()
      context.setReceiveTimeout(1.minute, ReceiveTimeout())
      ReplicatedEntityBehavior[Command, DomainEvent, Account](
        entityContext,
        emptyState = Account(AccountNo(entityContext.entityId), BigInt(0), ListMap()),
        commandHandler = (state, cmd) => state.applyCommand(cmd, logger),
        eventHandler = (state, evt) => state.applyEvent(evt),
      ).withStopMessage(Stop())
    }
  }
}
