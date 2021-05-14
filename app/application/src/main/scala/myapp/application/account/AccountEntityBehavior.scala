package myapp.application.account

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import myapp.adapter.Cursor
import myapp.application.serialize.KryoSerializable

object AccountEntityBehavior {

  sealed trait Command extends KryoSerializable

  final case class Deposit(cursor: Cursor, amount: BigDecimal, replyTo: ActorRef[DepositReply]) extends Command

  final case class DepositReply(cursor: Cursor, currentAmount: BigDecimal) extends KryoSerializable

  sealed trait Event extends KryoSerializable

  final case class Deposited(cursor: Cursor, amount: BigDecimal) extends Event

  final case class Account(balance: BigDecimal, latestCursor: Option[Cursor]) extends KryoSerializable {

    def deposit(amount: BigDecimal, cursor: Cursor): Account = {
      copy(balance = this.balance + amount, latestCursor = Option(cursor))
    }

    def onCommand(message: Command, context: ActorContext[Command]): Effect[Event, Account] = message match {
      case deposit: Deposit =>
        if (latestCursor.forall(_.lowerThan(deposit.cursor))) {
          Effect
            .persist[Event, Account](Deposited(deposit.cursor, deposit.amount))
            .thenRun { newState =>
              context.log.info(s"[${deposit.cursor}] Deposited: +${deposit.amount} (new balance: ${newState.balance})")
            }
            .thenReply(deposit.replyTo)(newState => DepositReply(deposit.cursor, newState.balance))
        } else {
          Effect
            .none[Event, Account]
            .thenRun { _ =>
              context.log.info(
                s"[${deposit.cursor}] Ignore - This deposit has already been processed: (amount: ${deposit.amount})",
              )
            }
            .thenReply(deposit.replyTo)(state => DepositReply(deposit.cursor, state.balance))
        }
    }

    def applyEvent(event: Event): Account = event match {
      case deposited: Deposited =>
        this.deposit(deposited.amount, deposited.cursor)
    }
  }

  def apply(entityType: String, entityId: String): Behavior[Command] = Behaviors.setup { context =>
    EventSourcedBehavior[Command, Event, Account](
      persistenceId = PersistenceId(entityType, entityId),
      emptyState = Account(balance = BigDecimal(0), latestCursor = None),
      commandHandler = (state, command) => state.onCommand(command, context),
      eventHandler = (state, event) => state.applyEvent(event),
    )
  }
}
