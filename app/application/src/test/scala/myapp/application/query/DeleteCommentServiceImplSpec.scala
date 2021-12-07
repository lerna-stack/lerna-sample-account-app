package myapp.application.query

import akka.actor.typed.ActorSystem
import com.typesafe.config.Config
import lerna.testkit.airframe.DISessionSupport
import lerna.testkit.akka.ScalaTestWithTypedActorTestKit
import myapp.adapter.account.{ AccountNo, TransactionId }
import myapp.adapter.query.DeleteCommentService.DeleteCommentResult
import myapp.application.projection.transaction.{
  TransactionEventType,
  TransactionRepository,
  TransactionRepositoryImpl,
}
import myapp.readmodel.{ JDBCSupport, ReadModeDIDesign }
import myapp.utility.scalatest.StandardSpec
import wvlet.airframe.{ newDesign, Design }

@SuppressWarnings(
  Array(
    "org.wartremover.contrib.warts.MissingOverride",
    "org.wartremover.warts.Equals",
  ),
)
class DeleteCommentServiceImplSpec
    extends ScalaTestWithTypedActorTestKit
    with StandardSpec
    with DISessionSupport
    with JDBCSupport {

  override protected val diDesign: Design = newDesign
    .add(ReadModeDIDesign.readModelDDesign)
    .bind[ActorSystem[Nothing]].toInstance(system)
    .bind[Config].toInstance(testKit.config)
    .bind[TransactionRepository].to[TransactionRepositoryImpl]

  private val service = diSession.build[DeleteCommentServiceImpl]

  import tableSeeds._
  import tables._
  import tables.profile.api._

  "DeleteCommentServiceImpl" should {
    "return Deleted if comment row which is associated with the given transaction was deleted" in withJDBC { db =>
      val accountNo     = "123-456"
      val transactionId = "0"
      db.prepare(
        TransactionStore += TransactionStoreRow(
          transactionId,
          TransactionEventType.Deposited.toString,
          accountNo,
          1000,
          1000,
          0,
        ),
      )
      db.prepare(CommentStore += CommentStoreRow(transactionId, "old comment"))

      val actual = service
        .delete(AccountNo(accountNo), TransactionId(transactionId), tenant)
        .futureValue
      expect(actual === DeleteCommentResult.Deleted)

      db.validate(CommentStore.filter(_.commentId === transactionId).result) { result =>
        expect(result.headOption.isEmpty)
      }
    }

    "return TransactionNotFound if the given transaction was not found" in withJDBC { _ =>
      val actual = service.delete(AccountNo("123-456"), TransactionId("0"), tenant).futureValue
      expect(actual === DeleteCommentResult.TransactionNotFound)
    }
  }

}
