package myapp.application.projection.deposit

import akka.actor.testkit.typed.scaladsl.LoggingTestKit
import akka.actor.typed.ActorSystem
import akka.projection.Projection
import akka.projection.scaladsl.SourceProvider
import akka.projection.testkit.scaladsl.{ ProjectionTestKit, TestSourceProvider }
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import lerna.testkit.airframe.DISessionSupport
import lerna.testkit.akka.ScalaTestWithTypedActorTestKit
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionId }
import myapp.application.projection.AppEventHandler.BehaviorSetup
import myapp.application.projection.deposit.DepositProjection.BankAccountApplicationUnavailable
import myapp.readmodel.{ JDBCSupport, ReadModeDIDesign }
import myapp.utility.AppRequestContext
import myapp.utility.scalatest.StandardSpec
import org.scalamock.scalatest.MockFactory
import wvlet.airframe._

import java.time.Instant
import scala.concurrent.Future

@SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride", "org.wartremover.warts.IsInstanceOf"))
class DepositProjectionSpec
    extends ScalaTestWithTypedActorTestKit
    with StandardSpec
    with MockFactory
    with DISessionSupport
    with JDBCSupport {

  private[this] val bankAccountMock = mock[BankAccountApplication]

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  override protected val diDesign: Design = newDesign
    .add(ReadModeDIDesign.readModelDDesign)
    .bind[Config].toInstance(testKit.config)
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

    /** [[Projection]] を作成する
      *
      * Projection の Source Provider は与えられた入金要求から作成される。
      */
    def createProjection(requests: Deposit*): Projection[Deposit] = {
      val source = Source(requests)
      val sourceProvider: SourceProvider[DepositProjection.Offset, Deposit] =
        TestSourceProvider[DepositProjection.Offset, Deposit](source, e => e.depositId.value)
      diSession.build[DepositProjection].createProjection(setup, sourceProvider)
    }

    "Source のデータを元に BankAccountApplication.deposit が呼び出される" in withJDBC { db =>
      val projection = createProjection(
        Deposit(DepositId(0L), accountNo = "ac-1", amount = BigInt(10), createdAt = Instant.now()),
        Deposit(DepositId(1L), accountNo = "ac-2", amount = BigInt(20), createdAt = Instant.now()),
      )

      (bankAccountMock
        .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
        .expects(AccountNo("ac-1"), TransactionId("DepositProjection-tenant-a:0"), BigInt(10), *)
        .returning(Future.successful(BankAccountApplication.DepositResult.Succeeded(10)))

      (bankAccountMock
        .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
        .expects(AccountNo("ac-2"), TransactionId("DepositProjection-tenant-a:1"), BigInt(20), *)
        .returning(Future.successful(BankAccountApplication.DepositResult.Succeeded(20)))

      projectionTestKit.run(projection) {
        db.validate(AkkaProjectionOffsetStore.result) { result =>
          // DepositId(1) が反映されて offset として保存されるのを確認する
          expect(result.headOption.exists(row => row.currentOffset === "1"))
        }
      }
    }

    "BankAccountApplication.deposit で ExcessBalance となった入金は破棄されて、後続の入金処理が継続する" in withJDBC { db =>
      val projection = createProjection(
        Deposit(DepositId(0L), accountNo = "ac-1", amount = BigInt(10), createdAt = Instant.now()),
        Deposit(DepositId(1L), accountNo = "ac-2", amount = BigInt(20), createdAt = Instant.now()),
      )

      (bankAccountMock
        .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
        .expects(AccountNo("ac-1"), TransactionId("DepositProjection-tenant-a:0"), BigInt(10), *)
        .returning(Future.successful(BankAccountApplication.DepositResult.ExcessBalance))

      (bankAccountMock
        .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
        .expects(AccountNo("ac-2"), TransactionId("DepositProjection-tenant-a:1"), BigInt(20), *)
        .returning(Future.successful(BankAccountApplication.DepositResult.Succeeded(20)))

      projectionTestKit.run(projection) {
        db.validate(AkkaProjectionOffsetStore.result) { result =>
          // 失敗するような入金があっても、後続の入金が処理されてオフセットが保存される
          expect(result.headOption.exists(row => row.currentOffset === "1"))
        }
      }
    }

    "BankAccountApplication.deposit で ExcessBalance となった場合にエラーログが出力される" in withJDBC { db =>
      val projection = createProjection(
        Deposit(DepositId(0L), accountNo = "ac-1", amount = BigInt(10), createdAt = Instant.now()),
      )

      (bankAccountMock
        .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
        .expects(AccountNo("ac-1"), TransactionId("DepositProjection-tenant-a:0"), BigInt(10), *)
        .returning(Future.successful(BankAccountApplication.DepositResult.ExcessBalance))

      LoggingTestKit.error("Deposit failed due to an excess balance").expect {
        projectionTestKit.run(projection) {
          db.validate(AkkaProjectionOffsetStore.result) { result =>
            // ログが確実に出力されることを保証するため、 Projection が完了するまで待つ
            expect(result.headOption.exists(row => row.currentOffset === "0"))
          }
        }
      }

    }

    "BankAccountApplication.deposit で Timeout となった場合に WARN ログが出力される" in withJDBC { db =>
      val projection = createProjection(
        Deposit(DepositId(0L), accountNo = "ac-1", amount = BigInt(10), createdAt = Instant.now()),
      )

      (bankAccountMock
        .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
        .expects(AccountNo("ac-1"), TransactionId("DepositProjection-tenant-a:0"), BigInt(10), *)
        .returning(Future.successful(BankAccountApplication.DepositResult.Timeout))

      LoggingTestKit.warn("Deposit failed due to the service being unavailable").expect {
        projectionTestKit.runWithTestSink(projection) { probe =>
          probe.request(1)
          val error = probe.expectError()
          expect(error.isInstanceOf[BankAccountApplicationUnavailable])
        }
      }
    }

  }
}
