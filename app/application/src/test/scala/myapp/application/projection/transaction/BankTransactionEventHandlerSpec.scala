package myapp.application.projection.transaction

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import akka.projection.Projection
import akka.projection.eventsourced.EventEnvelope
import akka.projection.scaladsl.SourceProvider
import akka.projection.testkit.scaladsl.{ProjectionTestKit, TestSourceProvider}
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import lerna.testkit.airframe.DISessionSupport
import lerna.testkit.akka.ScalaTestWithTypedActorTestKit
import lerna.util.trace.TraceId
import myapp.adapter.account.{AccountNo, TransactionId}
import myapp.application.account.BankAccountBehavior.{Deposited, DomainEvent, Refunded, Withdrew}
import myapp.application.projection.AppEventHandler.BehaviorSetup
import myapp.readmodel.{JDBCSupport, ReadModeDIDesign}
import myapp.utility.AppRequestContext
import myapp.utility.scalatest.StandardSpec
import myapp.utility.tenant.TenantA
import wvlet.airframe.{Design, newDesign}

@SuppressWarnings(
  Array(
    "org.wartremover.contrib.warts.MissingOverride",
    "org.wartremover.warts.Equals",
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

      val accountNo = AccountNo("1")
      val events: Vector[DomainEvent] = Vector[DomainEvent](
        Deposited(accountNo, TransactionId("id0"), BigInt(100000), 0L),
        Withdrew(accountNo, TransactionId("id1"), BigInt(5000), 0L),
        Withdrew(accountNo, TransactionId("id2"), BigInt(10000), 0L),
        Refunded(accountNo, TransactionId("id3"), TransactionId("id2"), BigInt(2000), 0L),
        Deposited(accountNo, TransactionId("id4"), BigInt(200000), 0L),
      )

      val envelopedEvents: Vector[EventEnvelope[DomainEvent]] = events.zipWithIndex.map {
        case (event, i) => createEnvelope(event, i)
      }

      val projection: Projection[EventEnvelope[DomainEvent]] = createProjection(envelopedEvents: _*)

      val expected = Vector(
        TransactionStoreRow("id0", "Deposited", "1", 100000, 0L),
        TransactionStoreRow("id1", "Withdrew", "1", 5000, 0L),
        TransactionStoreRow("id2", "Withdrew", "1", 10000, 0L),
        TransactionStoreRow("id3", "Refunded", "1", 2000, 0L),
        TransactionStoreRow("id4", "Deposited", "1", 200000, 0L),
      )

      projectionTestKit.run(projection) {
        db.validate(TransactionStore.result) { result =>
          expect(result === expected)
        }
      }
    }
  }
}
