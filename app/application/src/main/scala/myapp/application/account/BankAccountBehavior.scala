package myapp.application.account

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import com.fasterxml.jackson.annotation.{ JsonSubTypes, JsonTypeInfo }
import com.fasterxml.jackson.databind.annotation.{ JsonDeserialize, JsonSerialize }
import com.fasterxml.jackson.databind.util.StdConverter
import lerna.akka.entityreplication.typed._
import lerna.log.{ AppLogger, AppTypedActorLogging }
import myapp.adapter.account.TransactionId
import myapp.utility.AppRequestContext
import myapp.utility.tenant.AppTenant

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
  // GetBalanceReply
  final case class AccountBalance(balance: BigInt) extends Reply

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
  @JsonSubTypes(
    Array(
      new JsonSubTypes.Type(name = "Deposited", value = classOf[Deposited]),
      new JsonSubTypes.Type(name = "BalanceExceeded", value = classOf[BalanceExceeded]),
      new JsonSubTypes.Type(name = "Withdrew", value = classOf[Withdrew]),
      new JsonSubTypes.Type(name = "BalanceShorted", value = classOf[BalanceShorted]),
    ),
  )
  sealed trait DomainEvent {
    def appRequestContext: AppRequestContext
  }

  sealed trait DepositDomainEvent extends DomainEvent
  final case class Deposited(transactionId: TransactionId, amount: BigInt)(implicit
      val appRequestContext: AppRequestContext,
  ) extends DepositDomainEvent
  final case class BalanceExceeded(transactionId: TransactionId)(implicit
      val appRequestContext: AppRequestContext,
  ) extends DepositDomainEvent

  sealed trait WithdrawalDomainEvent extends DomainEvent
  final case class Withdrew(transactionId: TransactionId, amount: BigInt)(implicit
      val appRequestContext: AppRequestContext,
  ) extends WithdrawalDomainEvent
  final case class BalanceShorted(transactionId: TransactionId)(implicit
      val appRequestContext: AppRequestContext,
  ) extends WithdrawalDomainEvent

  type Effect = lerna.akka.entityreplication.typed.Effect[DomainEvent, Account]

  final case class Account(
      balance: BigInt,
      @JsonSerialize(converter = classOf[Account.ResentTransactionsSerializerConverter])
      @JsonDeserialize(converter = classOf[Account.ResentTransactionsDeserializerConverter])
      resentTransactions: ListMap[TransactionId, DomainEvent],
  ) {

    def deposit(amount: BigInt): Account =
      copy(balance = balance + amount)

    def withdraw(amount: BigInt): Account =
      copy(balance = balance - amount)

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
            case Some(_: WithdrawalDomainEvent) =>
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
                val event = Deposited(transactionId, amount)
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
            case Some(_: DepositDomainEvent) =>
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
                val event = Withdrew(transactionId, amount)
                Effect
                  .replicate[DomainEvent, Account](event)
                  .thenRun(logEvent(event, logger)(_))
                  .thenReply(replyTo)(state => WithdrawSucceeded(state.balance))
              }
          }
        case GetBalance(replyTo) =>
          Effect.reply(replyTo)(AccountBalance(balance))
        case ReceiveTimeout() =>
          Effect.passivate().thenNoReply()
        case Stop() =>
          Effect.stopLocally()
      }

    def applyEvent(event: DomainEvent): Account =
      event match {
        case Deposited(transactionId, amount) => deposit(amount).recordEvent(transactionId, event)
        case BalanceExceeded(transactionId)   => recordEvent(transactionId, event)
        case Withdrew(transactionId, amount)  => withdraw(amount).recordEvent(transactionId, event)
        case BalanceShorted(transactionId)    => recordEvent(transactionId, event)
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
        emptyState = Account(BigInt(0), ListMap()),
        commandHandler = (state, cmd) => state.applyCommand(cmd, logger),
        eventHandler = (state, evt) => state.applyEvent(evt),
      ).withStopMessage(Stop())
    }
  }
}
