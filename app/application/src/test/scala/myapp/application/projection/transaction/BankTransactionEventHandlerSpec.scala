package myapp.application.projection.transaction

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import akka.projection.Projection
import akka.projection.eventsourced.EventEnvelope
import akka.projection.scaladsl.SourceProvider
import akka.projection.testkit.scaladsl.{ ProjectionTestKit, TestSourceProvider }
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import lerna.testkit.airframe.DISessionSupport
import lerna.testkit.akka.ScalaTestWithTypedActorTestKit
import lerna.util.trace.TraceId
import myapp.adapter.account.TransactionId
import myapp.application.account.BankAccountBehavior.{ Deposited, DomainEvent, Refunded, Withdrew }
import myapp.application.projection.AppEventHandler.BehaviorSetup
import myapp.readmodel.{ JDBCSupport, ReadModeDIDesign }
import myapp.utility.AppRequestContext
import myapp.utility.scalatest.StandardSpec
import myapp.utility.tenant.TenantA
import wvlet.airframe.{ newDesign, Design }

@SuppressWarnings(
  Array(
    "org.wartremover.contrib.warts.MissingOverride",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.IsInstanceOf",
  ),
)
class BankTransactionEventHandlerSpec
    extends ScalaTestWithTypedActorTestKit()
    with StandardSpec
    with DISessionSupport
    with JDBCSupport {

  override protected val diDesign: Design = newDesign
    .add(ReadModeDIDesign.readModelDDesign)
    .bind[Config].toInstance(testKit.config)
    .bind[ActorSystem[Nothing]].toInstance(system)
    .bind[TransactionRepository].to[TransactionRepositoryImpl]

  "BankTransactionEventHandler" should {
    import tableSeeds._
    import tables._
    import tables.profile.api._

    val projectionTestKit = ProjectionTestKit(system)
    val setup             = BehaviorSetup(system, jdbcService.dbConfig, tenant)

    def createEnvelope(event: DomainEvent, seqNo: Long, timestamp: Long = 0L): EventEnvelope[DomainEvent] =
      EventEnvelope(Offset.sequence(seqNo), "persistenceId", seqNo, event, timestamp)

    def createProjection(requests: EventEnvelope[DomainEvent]*): Projection[EventEnvelope[DomainEvent]] = {
      val source = Source(requests)
      val sourceProvider: SourceProvider[Offset, EventEnvelope[DomainEvent]] =
        TestSourceProvider[Offset, EventEnvelope[DomainEvent]](source, extractOffset = env => env.offset)
      diSession.build[BankTransactionEventHandler].createProjection(setup, sourceProvider)
    }

    "process transaction events" in withJDBC { db =>
      implicit val appRequestContext: AppRequestContext = AppRequestContext(TraceId("trace_id"), TenantA)

      val events: Vector[DomainEvent] = Vector[DomainEvent](
        Deposited(TransactionId("id0"), BigInt(100000)),
        Withdrew(TransactionId("id1"), BigInt(5000)),
        Withdrew(TransactionId("id2"), BigInt(10000)),
        Refunded(TransactionId("id3"), TransactionId("id2"), BigInt(2000)),
        Deposited(TransactionId("id4"), BigInt(200000)),
      )

      val envelopedEvents: Vector[EventEnvelope[DomainEvent]] = events.zipWithIndex.map {
        case (event, i) => createEnvelope(event, i)
      }

      val projection: Projection[EventEnvelope[DomainEvent]] = createProjection(envelopedEvents: _*)

      val expected = Vector(
        TransactionStoreRow("id0", "Deposited", 100000),
        TransactionStoreRow("id1", "Withdrew", 5000),
        TransactionStoreRow("id2", "Withdrew", 10000),
        TransactionStoreRow("id3", "Refunded", 2000),
        TransactionStoreRow("id4", "Deposited", 200000),
      )

      projectionTestKit.run(projection) {
        db.validate(TransactionStore.result) { result =>
          result should contain theSameElementsAs expected
        }
      }
    }
  }
}
