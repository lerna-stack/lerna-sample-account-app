package myapp.application

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import myapp.adapter.Cursor

object AccountEntityBehavior {

  sealed trait Command

  final case class Deposit(cursor: Cursor, amount: BigDecimal, replyTo: ActorRef[DepositReply]) extends Command

  sealed trait DepositReply

  final case class Deposited(cursor: Cursor) extends DepositReply

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    Behaviors.receiveMessage {
      case deposit: Deposit =>
        context.log.info("deposit: {}", deposit)
        deposit.replyTo ! Deposited(deposit.cursor)
        Behaviors.same
    }
  }
}
