package myapp.application.deposit

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import myapp.adapter.DepositPoolGateway.Deposit
import myapp.adapter.{ Cursor, DepositPoolGateway }

import scala.util.{ Failure, Success }

private[deposit] object DepositSourceBehavior {

  sealed trait Command
  final case class Start(limit: Int, receiver: ActorRef[DemandReply]) extends Command
  final case class Ack()                                              extends Command
  final case class FetchedDeposits(deposits: Seq[Deposit])            extends Command
  final case class FetchingFailed(ex: Throwable)                      extends Command

  sealed trait DemandReply
  final case class Deposits(deposits: Seq[Deposit]) extends DemandReply
  final case class Failed(ex: Throwable)            extends DemandReply
  final case class Completed()                      extends DemandReply
}

import myapp.application.deposit.DepositSourceBehavior._

private[deposit] class DepositSourceBehavior(depositPoolGateway: DepositPoolGateway) {

  def createBehavior(
      cursor: Option[Cursor],
  ): Behavior[Command] = init(cursor)

  private[this] def init(
      cursor: Option[Cursor],
  ): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.receiveMessage {

        case Start(limit, receiver) =>
          context.self ! Ack()
          ready(cursor, limit, receiver)

        case _: Ack             => Behaviors.unhandled
        case _: FetchedDeposits => Behaviors.unhandled
        case _: FetchingFailed  => Behaviors.unhandled
      }
    }
  }

  private[this] def ready(
      cursor: Option[Cursor],
      limit: Int,
      receiver: ActorRef[DemandReply],
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {

        case Ack() =>
          context.pipeToSelf(depositPoolGateway.fetch(cursor, limit = limit)) {
            case Success(value) => FetchedDeposits(value.data)
            case Failure(ex)    => FetchingFailed(ex)
          }
          fetching(cursor, limit, receiver)

        case _: Start           => Behaviors.unhandled
        case _: FetchedDeposits => Behaviors.unhandled
        case _: FetchingFailed  => Behaviors.unhandled
      }
    }

  private[this] def fetching(
      cursor: Option[Cursor],
      limit: Int,
      receiver: ActorRef[DemandReply],
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {

        case FetchedDeposits(deposits) =>
          context.log.info("Fetched deposits (size: {})", deposits.size)
          if (deposits.size < limit) {
            receiver ! Deposits(deposits)
            receiver ! Completed()
            completed()
          } else {
            // size == limit
            receiver ! Deposits(deposits)
            ready(deposits.lastOption.map(_.cursor), limit, receiver)
          }

        case FetchingFailed(ex) =>
          context.log.warn("Fetching failed", ex)
          receiver ! Failed(ex)
          ready(cursor, limit, receiver)

        case _: Start => Behaviors.unhandled
        case _: Ack   => Behaviors.unhandled
      }
    }

  private[this] def completed(): Behavior[Command] =
    Behaviors.receiveMessage {
      case _: Ack => Behaviors.stopped

      case _: Start           => Behaviors.unhandled
      case _: FetchedDeposits => Behaviors.unhandled
      case _: FetchingFailed  => Behaviors.unhandled
    }
}
