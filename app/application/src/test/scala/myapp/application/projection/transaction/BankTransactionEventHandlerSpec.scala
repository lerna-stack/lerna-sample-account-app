package myapp.application.projection.transaction

import akka.Done
import akka.persistence.query.Offset
import akka.projection.ProjectionId
import akka.projection.eventsourced.EventEnvelope
import akka.projection.scaladsl.Handler
import akka.projection.testkit.scaladsl.{ ProjectionTestKit, TestProjection, TestSourceProvider }
import akka.stream.scaladsl.Source
import lerna.testkit.akka.ScalaTestWithTypedActorTestKit
import lerna.util.trace.TraceId
import myapp.adapter.account.TransactionId
import myapp.application.account.BankAccountBehavior.{ Deposited, DomainEvent, Refunded, Withdrew }
import myapp.application.projection.AppEventHandler
import myapp.utility.AppRequestContext
import myapp.utility.tenant.TenantA
import org.scalatest.wordspec.AnyWordSpecLike
import slick.dbio.DBIO

import scala.concurrent.{ ExecutionContext, ExecutionContextExecutor, Future }

object BankTransactionEventHandlerSpec {
  class MockTransactionRepository extends TransactionRepository {
    @SuppressWarnings(Array("org.wartremover.warts.Var"))
    var table: Vector[Transaction] = Vector[Transaction]()

    override def save(transaction: Transaction)(implicit ec: ExecutionContext): DBIO[Done] = DBIO.successful {
      table :+= transaction
      Done
    }
  }
}

class BankTransactionEventHandlerSpec extends ScalaTestWithTypedActorTestKit() with AnyWordSpecLike {
  import BankTransactionEventHandlerSpec._

  private val projectionTestKit = ProjectionTestKit(system)

  def createEnvelope(event: DomainEvent, seqNo: Long, timestamp: Long = 0L): EventEnvelope[DomainEvent] =
    EventEnvelope(Offset.sequence(seqNo), "persistenceId", seqNo, event, timestamp)

  def converter(
      handler: AppEventHandler[DomainEvent],
  )(implicit ec: ExecutionContext): Handler[EventEnvelope[DomainEvent]] = { envelope: EventEnvelope[DomainEvent] =>
    Future {
      handler.process(envelope)
      Done
    }
  }

  "BankTransactionEventHandler" should {
    "process transaction events" in {
      implicit val ec: ExecutionContextExecutor         = system.executionContext
      implicit val appRequestContext: AppRequestContext = AppRequestContext(TraceId("trace_id"), TenantA)
      val repository                                    = new MockTransactionRepository()
      val handler                                       = new BankTransactionEventHandler(system, repository)

      val events = Source(
        List(
          createEnvelope(Deposited(TransactionId("id0"), BigInt(100000)), 0L),
          createEnvelope(Withdrew(TransactionId("id1"), BigInt(5000)), 1L),
          createEnvelope(Withdrew(TransactionId("id2"), BigInt(10000)), 2L),
          createEnvelope(Refunded(TransactionId("id3"), TransactionId("id2"), BigInt(2000)), 3L),
          createEnvelope(Deposited(TransactionId("id4"), BigInt(200000)), 4L),
        ),
      )

      val projectionId = ProjectionId("name", "key")
      val sourceProvider =
        TestSourceProvider[Offset, EventEnvelope[DomainEvent]](events, extractOffset = env => env.offset)
      val projection = TestProjection(projectionId, sourceProvider, () => converter(handler))

      projectionTestKit.run(projection) {
        repository.table shouldBe Vector(
          Transaction("id0", "Deposited", 100000),
          Transaction("id1", "Withdrew", 5000),
          Transaction("id2", "Withdrew", 10000),
          Transaction("id3", "Refunded", 2000),
          Transaction("id4", "Deposited", 200000),
        )
      }
    }
  }
}
