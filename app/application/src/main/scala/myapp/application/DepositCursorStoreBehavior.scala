package myapp.application

import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl._
import myapp.adapter.Cursor

object DepositCursorStoreBehavior {

  sealed trait Command

  final case class GetCursor(replyTo: ActorRef[GetCursorReply]) extends Command
  final case class SaveCursor(cursor: Cursor)                   extends Command

  final case class GetCursorReply(cursor: Option[Cursor])

  sealed trait Event

  final case class CursorRecorded(cursor: Cursor) extends Event

  final case class State(latestCursor: Option[Cursor]) {

    def onCommand(
        command: Command,
    ): ReplyEffect[Event, State] = command match {

      case GetCursor(replyTo) =>
        Effect
          .reply(replyTo)(GetCursorReply(latestCursor))

      case SaveCursor(cursor) =>
        Effect
          .persist(CursorRecorded(cursor))
          .thenNoReply()
    }

    def applyEvent(event: Event): State = event match {
      case CursorRecorded(cursor) => copy(Option(cursor))
    }
  }
}

import DepositCursorStoreBehavior._

class DepositCursorStoreBehavior {

  def createBehavior(): Behavior[Command] = {
    EventSourcedBehavior.withEnforcedReplies[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId("test" /*TODO: set*/ ),
      emptyState = State(latestCursor = None),
      commandHandler = (state, command) => state.onCommand(command),
      eventHandler = (state, event) => state.applyEvent(event),
    )
  }
}
