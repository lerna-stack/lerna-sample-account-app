package myapp.application

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import myapp.adapter.{ Cursor, DepositPoolGateway }
import myapp.adapter.DepositPoolGateway.Deposit

import scala.util.{ Failure, Success }

object DepositSourceBehavior {

  sealed trait Command
  final case class Start(receiver: ActorRef[DemandReply])  extends Command
  final case class Demand()                                extends Command
  final case class FetchedDeposits(deposits: Seq[Deposit]) extends Command
  final case class FetchingFailed(ex: Throwable)           extends Command

  sealed trait DemandReply
  final case class Deposits(deposits: Seq[Deposit]) extends DemandReply
  final case class Failed(ex: Throwable)            extends DemandReply
  final case class Completed()                      extends DemandReply
}

import DepositSourceBehavior._

class DepositSourceBehavior(depositPoolGateway: DepositPoolGateway) {

  def createBehavior(
      cursor: Option[Cursor],
  ): Behavior[Command] = init(cursor)

  private[this] def init(
      cursor: Option[Cursor],
  ): Behavior[Command] = Behaviors.receiveMessage {

    case Start(receiver) =>
      ready(cursor, receiver)

    case _: Demand          => Behaviors.unhandled
    case _: FetchedDeposits => Behaviors.unhandled
    case _: FetchingFailed  => Behaviors.unhandled
  }

  private[this] def ready(
      cursor: Option[Cursor],
      receiver: ActorRef[DemandReply],
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {

        case Demand() =>
          context.pipeToSelf(depositPoolGateway.fetch(cursor, limit = 1000)) {
            case Success(value) => FetchedDeposits(value.data)
            case Failure(ex)    => FetchingFailed(ex)
          }
          fetching(cursor, receiver)

        case _: Start           => Behaviors.unhandled
        case _: FetchedDeposits => Behaviors.unhandled
        case _: FetchingFailed  => Behaviors.unhandled
      }
    }

  private[this] def fetching(
      cursor: Option[Cursor],
      receiver: ActorRef[DemandReply],
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {

        case FetchedDeposits(deposits) =>
          deposits.lastOption match {
            case Some(last) =>
              receiver ! Deposits(deposits)
              ready(Option(last.cursor), receiver)
            case None =>
              receiver ! Completed()
              ready(cursor, receiver)
          }

        case FetchingFailed(ex) =>
          context.log.warn("fetch error", ex)
          receiver ! Failed(ex)
          ready(cursor, receiver)

        case _: Start  => Behaviors.unhandled
        case _: Demand => Behaviors.unhandled
      }
    }
}
