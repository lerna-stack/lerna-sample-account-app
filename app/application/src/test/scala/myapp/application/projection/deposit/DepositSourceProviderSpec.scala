package myapp.application.projection.deposit

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorSystem
import akka.stream.testkit.scaladsl.TestSink
import com.typesafe.config.{ Config, ConfigFactory }
import lerna.testkit.airframe.DISessionSupport
import myapp.readmodel.{ JDBCSupport, ReadModeDIDesign }
import myapp.utility.scalatest.StandardSpec
import myapp.utility.tenant.{ AppTenant, TenantA }
import wvlet.airframe._
import org.scalatest.concurrent.ScalaFutures._

import scala.concurrent.Future

class DepositSourceProviderSpec extends StandardSpec with DISessionSupport with JDBCSupport {

  private[this] val testkit = ActorTestKit()

  implicit val system: ActorSystem[Nothing] = testkit.system

  override protected val diDesign: Design = newDesign
    .add(ReadModeDIDesign.readModelDDesign)
    .bind[ActorSystem[Nothing]].toInstance(system)
    .bind[Config].toInstance {
      ConfigFactory
        .parseString {
          """
          myapp.application.projection.deposit {
            polling-batch-size = 3
          }
          """
        }.withFallback(testkit.config)
    }
    .bind[AppTenant].toInstance(TenantA)

  import tableSeeds._
  import tables.profile.api._
  import tables._

  "DepositSourceProvider" should {

    val sourceProvider = diSession.build[DepositSourceProvider]
    val testSink       = TestSink[Deposit]()

    "offset がないときは全てのデータを提供する" in withJDBC { db =>
      db.prepare(
        DepositStore += tableSeeds.DepositStoreRowSeed.copy(depositId = 1L),
        DepositStore += tableSeeds.DepositStoreRowSeed.copy(depositId = 2L),
        DepositStore += tableSeeds.DepositStoreRowSeed.copy(depositId = 3L),
      )

      whenReady(sourceProvider.source(() => emptyOffset)) { source =>
        val probe  = source.runWith(testSink)
        val result = probe.request(3).expectNextN(3)
        expect(result.map(_.depositId.value) === Seq(1L, 2L, 3L))
      }
    }

    "offset があるときはその offset よりも大きい offset を持つデータのみ提供する" in withJDBC { db =>
      db.prepare(
        DepositStore += tableSeeds.DepositStoreRowSeed.copy(depositId = 1L),
        DepositStore += tableSeeds.DepositStoreRowSeed.copy(depositId = 2L),
        DepositStore += tableSeeds.DepositStoreRowSeed.copy(depositId = 3L),
      )

      whenReady(sourceProvider.source(() => offset(1L))) { source =>
        val probe  = source.runWith(testSink)
        val result = probe.request(3).expectNextN(2)
        expect(result.map(_.depositId.value) === Seq(2L, 3L))
      }
    }

    "insert 順に関わらず offset の昇順でデータを提供する" in withJDBC { db =>
      db.prepare(
        DepositStore += tableSeeds.DepositStoreRowSeed.copy(depositId = 2L),
        DepositStore += tableSeeds.DepositStoreRowSeed.copy(depositId = 3L),
        DepositStore += tableSeeds.DepositStoreRowSeed.copy(depositId = 1L),
      )

      whenReady(sourceProvider.source(() => emptyOffset)) { source =>
        val probe  = source.runWith(testSink)
        val result = probe.request(3).expectNextN(3)
        expect(result.map(_.depositId.value) === Seq(1L, 2L, 3L))
      }
    }

    "バッチサイズ以上のデータがあっても全件取得できる" in withJDBC { db =>
      val config = diSession.build[DepositProjectionConfig]
      expect(config.pollingBatchSize === 3)

      db.prepare(
        DepositStore += tableSeeds.DepositStoreRowSeed.copy(depositId = 1L),
        DepositStore += tableSeeds.DepositStoreRowSeed.copy(depositId = 2L),
        DepositStore += tableSeeds.DepositStoreRowSeed.copy(depositId = 3L),
        DepositStore += tableSeeds.DepositStoreRowSeed.copy(depositId = 4L),
      )

      whenReady(sourceProvider.source(() => emptyOffset)) { source =>
        val probe  = source.runWith(testSink)
        val result = probe.request(4).expectNextN(4)
        expect(result.map(_.depositId.value) === Seq(1L, 2L, 3L, 4L))
      }
    }
  }

  def emptyOffset: Future[Option[DepositProjection.Offset]] = {
    Future.successful(None)
  }

  def offset(offset: DepositProjection.Offset): Future[Option[DepositProjection.Offset]] = {
    Future.successful(Option(offset))
  }
}
