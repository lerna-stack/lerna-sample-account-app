package myapp.application.query

import com.typesafe.config.Config
import lerna.testkit.airframe.DISessionSupport
import lerna.testkit.akka.ScalaTestWithTypedActorTestKit
import myapp.adapter.account.{ AccountNo, TransactionDto }
import myapp.adapter.query.GetTransactionListService
import myapp.readmodel.{ JDBCSupport, ReadModeDIDesign }
import myapp.utility.scalatest.StandardSpec
import myapp.utility.tenant.TenantA
import org.scalatest.prop.TableDrivenPropertyChecks
import wvlet.airframe.{ newDesign, Design }

@SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
final class GetTransactionListServiceImplSpec
    extends ScalaTestWithTypedActorTestKit
    with StandardSpec
    with TableDrivenPropertyChecks
    with DISessionSupport
    with JDBCSupport {
  override protected val diDesign: Design = newDesign
    .add(ReadModeDIDesign.readModelDDesign)
    .bind[Config].toInstance(testKit.config)

  private val repository: GetTransactionListService = diSession.build[GetTransactionListServiceImpl]

  import tableSeeds._
  import tables._
  import tables.profile.api._

  "get transactions by accountNo" in withJDBC { db =>
    val accountNo = "123-456"

    val transactionRows = Seq(
      TransactionStoreRow("0", "Deposited", accountNo, 1000, 1000, 0),
      TransactionStoreRow("1", "Withdrew", accountNo, 100, 900, 1),
      TransactionStoreRow("2", "Refunded", accountNo, 50, 950, 2),
    )
    db.prepare(TransactionStore ++= transactionRows)

    val commentRows = Seq(
      CommentStoreRow("0", "comment0"),
      CommentStoreRow("1", "comment1"),
    )
    db.prepare(CommentStore ++= commentRows)

    val table = Table(
      ("accountNo", "tenant", "offset", "limit", "expected"),
      (
        AccountNo(accountNo),
        TenantA,
        0,
        100,
        Seq(
          TransactionDto("0", "Deposited", 1000, 1000, 0L, "comment0"),
          TransactionDto("1", "Withdrew", 100, 900, 1L, "comment1"),
          TransactionDto("2", "Refunded", 50, 950, 2L, ""),
        ),
      ),
      (
        AccountNo(accountNo),
        TenantA,
        1,
        1,
        Seq(
          TransactionDto("1", "Withdrew", 100, 900, 1L, "comment1"),
        ),
      ),
    )

    forAll(table) { (accountNo, tenant, offset, limit, expected) =>
      val actual = repository.getTransactionList(accountNo, tenant, offset, limit).futureValue
      expect(actual === expected)
    }
  }
}
