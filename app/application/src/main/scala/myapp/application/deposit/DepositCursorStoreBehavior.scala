package myapp.application.deposit

import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl._
import lerna.util.lang.Equals._
import myapp.adapter.Cursor
import myapp.application.serialize.KryoSerializable

private[deposit] object DepositCursorStoreBehavior {

  sealed trait Command

  final case class GetCursor(replyTo: ActorRef[GetCursorReply]) extends Command
  final case class SaveCursor(cursor: Cursor)                   extends Command
  final case class Stop()                                       extends Command

  final case class GetCursorReply(cursor: Option[Cursor])

  sealed trait Event extends KryoSerializable

  final case class CursorRecorded(cursor: Cursor) extends Event

  final case class State(latestCursor: Option[Cursor]) extends KryoSerializable {

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

      case Stop() =>
        Effect.stop().thenNoReply()
    }

    def applyEvent(event: Event): State = event match {
      case CursorRecorded(cursor) => copy(Option(cursor))
    }
  }
}

import myapp.application.deposit.DepositCursorStoreBehavior._

private[deposit] class DepositCursorStoreBehavior {

  def createBehavior(persistenceId: PersistenceId): Behavior[Command] = {
    EventSourcedBehavior
      .withEnforcedReplies[Command, Event, State](
        persistenceId = persistenceId,
        emptyState = State(latestCursor = None),
        commandHandler = (state, command) => state.onCommand(command),
        eventHandler = (state, event) => state.applyEvent(event),
      )
      .snapshotWhen((_, _, seqNr) => seqNr % 10 === 0L)
      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
  }
}
