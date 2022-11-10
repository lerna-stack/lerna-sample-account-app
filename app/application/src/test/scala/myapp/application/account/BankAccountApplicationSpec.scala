package myapp.application.account

import akka.actor.testkit.typed.scaladsl.LoggingTestKit
import akka.actor.typed.ActorSystem
import com.typesafe.config.{ Config, ConfigFactory }
import lerna.testkit.airframe.DISessionSupport
import lerna.testkit.akka.ScalaTestWithTypedActorTestKit
import lerna.util.trace.TraceId
import myapp.adapter.account.BankAccountApplication.{
  DepositResult,
  FetchBalanceResult,
  RefundResult,
  WithdrawalResult,
}
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionId }
import myapp.utility.AppRequestContext
import myapp.utility.scalatest.StandardSpec
import myapp.utility.tenant.TenantA
import wvlet.airframe.{ newDesign, Design }

class BankAccountApplicationSpec extends ScalaTestWithTypedActorTestKit() with StandardSpec with DISessionSupport {
  override protected val diDesign: Design = newDesign
    .bind[Config].toInstance(
      ConfigFactory
        .parseString(s"""
         | // 90はAccountNo("123")から算出されるshard id
         | myapp.application.lerna.under-maintenance-shards = ["90"]
         |""".stripMargin)
        .withFallback(testKit.config),
    )
    .bind[ActorSystem[Nothing]].toInstance(system)
    .bind[BankAccountApplication].to[BankAccountApplicationImpl]

  private val application = diSession.build[BankAccountApplication]

  implicit private val appRequestContext: AppRequestContext = AppRequestContext(TraceId.unknown, TenantA)

  "BankAccountApplication fetchBalance" should {
    "return UnderMaintenance if a raft actor is under maintenance" in {
      // AccountNo("123") から算出されるshard idは90
      val accountNo = AccountNo("123")
      LoggingTestKit.warn("The raft actor(shard id = 90) is under maintenance").expect {
        whenReady(application.fetchBalance(accountNo)) { result =>
          expect(result === FetchBalanceResult.UnderMaintenance)
        }
      }
    }
  }

  "BankAccountApplication deposit" should {
    "return UnderMaintenance if a raft actor is under maintenance" in {
      // AccountNo("123") から算出されるshard idは90
      val accountNo     = AccountNo("123")
      val transactionId = TransactionId("transaction_id")
      LoggingTestKit.warn("The raft actor(shard id = 90) is under maintenance").expect {
        whenReady(application.deposit(accountNo, transactionId, 10000)) { result =>
          expect(result === DepositResult.UnderMaintenance)
        }
      }
    }
  }

  "BankAccountApplication withdraw" should {
    "return UnderMaintenance if a raft actor is under maintenance" in {
      // AccountNo("123") から算出されるshard idは90
      val accountNo     = AccountNo("123")
      val transactionId = TransactionId("transaction_id")
      LoggingTestKit.warn("The raft actor(shard id = 90) is under maintenance").expect {
        whenReady(application.withdraw(accountNo, transactionId, 10000)) { result =>
          expect(result === WithdrawalResult.UnderMaintenance)
        }
      }
    }
  }

  "BankAccountApplication refund" should {
    "return UnderMaintenance if a raft actor is under maintenance" in {
      // AccountNo("123") から算出されるshard idは90
      val accountNo             = AccountNo("123")
      val transactionId         = TransactionId("transaction_id")
      val withdrawTransactionId = TransactionId("withdraw_transaction_id")
      LoggingTestKit.warn("The raft actor(shard id = 90) is under maintenance").expect {
        whenReady(application.refund(accountNo, transactionId, withdrawTransactionId, 10000)) { result =>
          expect(result === RefundResult.UnderMaintenance)
        }
      }
    }
  }
}
