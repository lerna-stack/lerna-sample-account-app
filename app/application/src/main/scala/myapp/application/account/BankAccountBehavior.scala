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
import myapp.application.account.BankAccountBehavior.DomainEvent.EventNo
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

    /** 口座番号
      *
      * データ不整合となるような事象(イベントの誤配送)を早期に検出するために使用する。
      */
    def accountNo: AccountNo

    /** イベント番号
      *
      * データ不整合となるような事象(イベントの重複や欠落)を早期に検出するために使用する。
      * データ不整合となるような事象が発生しなければ、必ず1ずつ増加する。
      */
    def eventNo: DomainEvent.EventNo

    def appRequestContext: AppRequestContext

  }
  object DomainEvent {
    final case class EventNo(value: Long) extends AnyVal {
      def next: EventNo = EventNo(value + 1)
    }
  }

  sealed trait DepositDomainEvent extends DomainEvent
  final case class Deposited(
      accountNo: AccountNo,
      eventNo: EventNo,
      transactionId: TransactionId,
      amount: BigInt,
      balance: BigInt,
      transactedAt: Long,
  )(implicit
      val appRequestContext: AppRequestContext,
  ) extends DepositDomainEvent
  final case class BalanceExceeded(accountNo: AccountNo, eventNo: EventNo, transactionId: TransactionId)(implicit
      val appRequestContext: AppRequestContext,
  ) extends DepositDomainEvent

  sealed trait WithdrawalDomainEvent extends DomainEvent
  final case class Withdrew(
      accountNo: AccountNo,
      eventNo: EventNo,
      transactionId: TransactionId,
      amount: BigInt,
      balance: BigInt,
      transactedAt: Long,
  )(implicit
      val appRequestContext: AppRequestContext,
  ) extends WithdrawalDomainEvent
  final case class BalanceShorted(accountNo: AccountNo, eventNo: EventNo, transactionId: TransactionId)(implicit
      val appRequestContext: AppRequestContext,
  ) extends WithdrawalDomainEvent

  sealed trait RefundDomainEvent extends DomainEvent
  final case class Refunded(
      accountNo: AccountNo,
      eventNo: EventNo,
      transactionId: TransactionId,
      withdrawalTransactionId: TransactionId,
      amount: BigInt,
      balance: BigInt,
      transactedAt: Long,
  )(implicit val appRequestContext: AppRequestContext)
      extends RefundDomainEvent
  final case class InvalidRefundRequested(
      accountNo: AccountNo,
      eventNo: EventNo,
      transactionId: TransactionId,
      withdrawalTransactionId: TransactionId,
      amount: BigInt,
  )(implicit
      val appRequestContext: AppRequestContext,
  ) extends RefundDomainEvent

  type Effect = lerna.akka.entityreplication.typed.Effect[DomainEvent, Account]

  /** 口座
    *
    * BankAccountBehavior の状態 を表す。
    *
    * @param lastAppliedEventNo 最後に適用したイベント番号を表す。
    *                           この状態でレプリケーションするイベントには `lastAppliedEventNo + 1` を設定すること。
    *                           この状態に適用できるイベントのイベント番号は `lastAppliedEventNo + 1` である。
    *                           `lastAppliedEventNo + 1` 以外のイベント番号を持つイベントの適用が発生した場合、
    *                           データ不整合となるような不具合が発生している可能性がある。
    *                           最後に適用したイベントがない場合、`EventNo(0)` とする。
    */
  final case class Account(
      accountNo: AccountNo,
      lastAppliedEventNo: EventNo,
      balance: BigInt,
      @JsonSerialize(converter = classOf[Account.RecentTransactionsSerializerConverter])
      @JsonDeserialize(converter = classOf[Account.RecentTransactionsDeserializerConverter])
      recentTransactions: ListMap[TransactionId, DomainEvent],
  ) {

    def withBalance(balance: BigInt): Account = copy(balance = balance)

    def withLastAppliedEventNo(lastAppliedEventNo: EventNo): Account =
      copy(lastAppliedEventNo = lastAppliedEventNo)

    private[this] val maxRecentTransactionSize = 30

    def recordEvent(transactionId: TransactionId, event: DomainEvent): Account =
      copy(recentTransactions = (recentTransactions + (transactionId -> event)).takeRight(maxRecentTransactionSize))

    @SuppressWarnings(Array("lerna.warts.CyclomaticComplexity"))
    def applyCommand(command: Command, logger: AppLogger): Effect =
      command match {
        case command @ Deposit(transactionId, amount, replyTo) =>
          import command.appRequestContext
          recentTransactions.get(transactionId) match {
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
                val event = BalanceExceeded(accountNo, lastAppliedEventNo.next, transactionId)
                Effect
                  .replicate[DomainEvent, Account](event)
                  .thenRun(logEvent(event, logger)(_))
                  .thenReply(replyTo)(_ => ExcessBalance())
              } else {
                val event = Deposited(
                  accountNo,
                  lastAppliedEventNo.next,
                  transactionId,
                  amount,
                  amount + balance,
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
          recentTransactions.get(transactionId) match {
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
                val event = BalanceShorted(accountNo, lastAppliedEventNo.next, transactionId)
                Effect
                  .replicate[DomainEvent, Account](event)
                  .thenRun(logEvent(event, logger)(_))
                  .thenReply(replyTo)(_ => ShortBalance())
              } else {
                val event = Withdrew(
                  accountNo,
                  lastAppliedEventNo.next,
                  transactionId,
                  amount,
                  balance - amount,
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
      recentTransactions.get(transactionId) match {
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
            val event =
              InvalidRefundRequested(
                accountNo,
                lastAppliedEventNo.next,
                transactionId,
                withdrawalTransactionId,
                refundAmount,
              )
            Effect
              .replicate[DomainEvent, Account](event)
              .thenRun(logEvent(event, logger)(_))
              .thenReply(replyTo)(_ => InvalidRefundCommand())
          } else {
            val event = Refunded(
              accountNo,
              lastAppliedEventNo.next,
              transactionId,
              withdrawalTransactionId,
              refundAmount,
              balance + refundAmount,
              ZonedDateTime.now().toEpochSecond,
            )
            Effect
              .replicate[DomainEvent, Account](event)
              .thenRun(logEvent(event, logger)(_))
              .thenReply(replyTo)(state => RefundSucceeded(state.balance))
          }
      }
    }

    def applyEvent(event: DomainEvent): Account = {
      require(
        event.eventNo === lastAppliedEventNo.next,
        s"The event number of the event [${event.toString}] should be equal to the applied event number [${lastAppliedEventNo.toString}] plus one",
      )
      require(
        event.accountNo === accountNo,
        s"The account number of the event [${event.toString}] should be equal to the account number of the state [${accountNo.toString}]",
      )
      event match {
        case Deposited(_, _, transactionId, _, newBalance, _) =>
          withLastAppliedEventNo(event.eventNo)
            .withBalance(newBalance)
            .recordEvent(transactionId, event)
        case BalanceExceeded(_, _, transactionId) =>
          withLastAppliedEventNo(event.eventNo)
            .recordEvent(transactionId, event)
        case Withdrew(_, _, transactionId, _, newBalance, _) =>
          withLastAppliedEventNo(event.eventNo)
            .withBalance(newBalance)
            .recordEvent(transactionId, event)
        case BalanceShorted(_, _, transactionId) =>
          withLastAppliedEventNo(event.eventNo)
            .recordEvent(transactionId, event)
        case Refunded(_, _, transactionId, _, _, newBalance, _) =>
          withLastAppliedEventNo(event.eventNo)
            .withBalance(newBalance)
            .recordEvent(transactionId, event)
        case InvalidRefundRequested(_, _, transactionId, _, _) =>
          withLastAppliedEventNo(event.eventNo)
            .recordEvent(transactionId, event)
      }
    }

    private[this] def logEvent(event: DomainEvent, logger: AppLogger)(
        state: Account,
    )(implicit appRequestContext: AppRequestContext): Unit = {
      val ANSI_YELLOW = "\u001B[33m"
      val ANSI_RESET  = "\u001B[0m"
      logger.info(
        s"${ANSI_YELLOW}[LEADER]${ANSI_RESET} ${event.toString} [balance: ${state.balance.toString}, recent-transactions: ${state.recentTransactions.size.toString}]",
      )
    }
  }

  object Account {

    private[BankAccountBehavior] class RecentTransactionsSerializerConverter
        extends StdConverter[ListMap[TransactionId, DomainEvent], List[(TransactionId, DomainEvent)]] {
      override def convert(value: ListMap[TransactionId, DomainEvent]): List[(TransactionId, DomainEvent)] =
        value.toList
    }

    private[BankAccountBehavior] class RecentTransactionsDeserializerConverter
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
        emptyState = Account(
          AccountNo(entityContext.entityId),
          lastAppliedEventNo = EventNo(0),
          balance = BigInt(0),
          recentTransactions = ListMap(),
        ),
        commandHandler = (state, cmd) => state.applyCommand(cmd, logger),
        eventHandler = (state, evt) => state.applyEvent(evt),
      ).withStopMessage(Stop())
    }
  }
}
