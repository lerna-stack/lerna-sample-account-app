package myapp.application.projection.deposit

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorSystem
import akka.projection.scaladsl.SourceProvider
import akka.projection.testkit.scaladsl.{ ProjectionTestKit, TestSourceProvider }
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import lerna.testkit.airframe.DISessionSupport
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionId }
import myapp.application.projection.AppEventHandler.BehaviorSetup
import myapp.readmodel.{ JDBCSupport, ReadModeDIDesign }
import myapp.utility.AppRequestContext
import myapp.utility.scalatest.StandardSpec
import org.scalamock.scalatest.MockFactory
import wvlet.airframe._

import java.time.Instant
import scala.concurrent.Future

@SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
class DepositProjectionSpec extends StandardSpec with MockFactory with DISessionSupport with JDBCSupport {

  private[this] val actorTestKit = ActorTestKit()

  private[this] implicit val system: ActorSystem[Nothing] = actorTestKit.system

  private[this] val bankAccountMock = mock[BankAccountApplication]

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  override protected val diDesign: Design = newDesign
    .add(ReadModeDIDesign.readModelDDesign)
    .bind[Config].toInstance(actorTestKit.config)
    .bind[ActorSystem[Nothing]].toInstance(system)
    .bind[BankAccountApplication].toInstance(bankAccountMock)
    // DepositSourceProvider が要求するコンポーネントを準備しなくて良い状態にするため mock に差し替えておく
    // SourceProvider はテストケースごとに TestSourceProvider を使う
    .bind[DepositSourceProvider].toInstance(mock[DepositSourceProvider])

  "DepositProjection" should {
    import tableSeeds._
    import tables._
    import tables.profile.api._

    val projectionTestKit = ProjectionTestKit(system)
    val setup             = BehaviorSetup(system, jdbcService.dbConfig, tenant)

    "Source のデータを元に BankAccountApplication.deposit が呼び出される" in withJDBC { db =>
      val source = Source(
        Seq(
          Deposit(DepositId(0L), accountNo = "ac-1", amount = BigInt(10), createdAt = Instant.now()),
          Deposit(DepositId(1L), accountNo = "ac-2", amount = BigInt(20), createdAt = Instant.now()),
        ),
      )

      val sourceProvider: SourceProvider[DepositProjection.Offset, Deposit] =
        TestSourceProvider[DepositProjection.Offset, Deposit](source, e => e.depositId.value)

      val projection = diSession.build[DepositProjection].createProjection(setup, sourceProvider)

      (bankAccountMock
        .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
        .expects(AccountNo("ac-1"), TransactionId("DepositProjection-tenant-a:0"), BigInt(10), *)
        .returning(Future.successful(BigInt(10)))

      (bankAccountMock
        .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
        .expects(AccountNo("ac-2"), TransactionId("DepositProjection-tenant-a:1"), BigInt(20), *)
        .returning(Future.successful(BigInt(20)))

      projectionTestKit.run(projection) {
        db.validate(AkkaProjectionOffsetStore.result) { result =>
          // DepositId(1) が反映されて offset として保存されるのを確認する
          expect(result.headOption.exists(row => row.currentOffset === "1"))
        }
      }
    }
  }
}
