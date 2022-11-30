package myapp.dataviewer

import akka.Done
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import akka.stream.scaladsl.Sink
import myapp.utility.scalatest.SpecAssertions
import org.scalatest.Inside
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.DurationInt

object CassandraDataViewerSpec {
  object TestActor {
    sealed trait Command
    final case class Save(count: Int, replyTo: ActorRef[Done]) extends Command

    sealed trait Event
    final case class Saved(count: Int) extends Event with JsonSerializable

    final case class State(count: Int) extends JsonSerializable

    def apply(persistenceId: PersistenceId, journalPluginId: String): Behavior[Command] =
      EventSourcedBehavior[Command, Event, State](
        persistenceId = persistenceId,
        emptyState = State(0),
        commandHandler = (state, cmd) =>
          cmd match {
            case save: Save => Effect.persist(Saved(save.count)).thenReply(save.replyTo)(_ => Done)
          },
        eventHandler = (state, evt) =>
          evt match {
            case Saved(count) => state.copy(state.count + count)
          },
      ).withJournalPluginId(journalPluginId)
  }
}

class CassandraDataViewerSpec extends ScalaTestWithActorTestKit() with SpecAssertions with AnyWordSpecLike with Inside {
  import CassandraDataViewerSpec._
  import TestActor._

  private val configPath = "akka-entity-replication.raft.persistence.cassandra-tenant-a"

  "CassandraDataViewer.fetch" should {
    val persistenceId = PersistenceId.ofUniqueId(s"dummy-persistence-id-${System.currentTimeMillis().toString}")
    val actor         = spawn(TestActor(persistenceId, s"$configPath.journal"))

    val count1 = 11
    val count2 = 44
    val count3 = 99

    val probe = createTestProbe[Done]()
    actor ! Save(count1, probe.ref)
    actor ! Save(count2, probe.ref)
    actor ! Save(count3, probe.ref)
    probe.receiveMessages(3, 20.seconds)

    val raftViewer = new CassandraDataViewer(configPath)

    "return events" in {
      val fromSequenceNr: Long = 0
      val toSequenceNr: Long   = 10
      val persistenceType      = DataViewer.PersistenceType.Event(persistenceId.id, fromSequenceNr, toSequenceNr)

      val results = raftViewer
        .fetch(persistenceType)
        .runWith(Sink.seq)
        .futureValue

      expect {
        results === List(
          (1, Saved(count1)),
          (2, Saved(count2)),
          (3, Saved(count3)),
        )
      }
    }
  }
}
