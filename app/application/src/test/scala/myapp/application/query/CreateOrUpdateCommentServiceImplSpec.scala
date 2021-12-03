package myapp.application.query

import akka.actor.typed.ActorSystem
import com.typesafe.config.Config
import lerna.testkit.airframe.DISessionSupport
import lerna.testkit.akka.ScalaTestWithTypedActorTestKit
import myapp.adapter.Comment
import myapp.adapter.account.{ AccountNo, TransactionId }
import myapp.adapter.query.CreateOrUpdateCommentService.CreateOrUpdateCommentResult
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
class CreateOrUpdateCommentServiceImplSpec
    extends ScalaTestWithTypedActorTestKit
    with StandardSpec
    with DISessionSupport
    with JDBCSupport {

  override protected val diDesign: Design = newDesign
    .add(ReadModeDIDesign.readModelDDesign)
    .bind[ActorSystem[Nothing]].toInstance(system)
    .bind[Config].toInstance(testKit.config)
    .bind[TransactionRepository].to[TransactionRepositoryImpl]

  private val service = diSession.build[CreateOrUpdateCommentServiceImpl]

  import tableSeeds._
  import tables._
  import tables.profile.api._

  "CreateOrUpdateCommentServiceImpl" should {
    "return Created if comment row which is associated with given transaction was newly created" in withJDBC { db =>
      val accountNo     = "123-456"
      val transactionId = "0"
      val newComment    = "created"
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

      val actual =
        service
          .createOrUpdate(AccountNo(accountNo), TransactionId(transactionId), Comment(newComment), tenant).futureValue
      expect(actual === CreateOrUpdateCommentResult.Created)
      db.validate(CommentStore.filter(_.commentId === transactionId).result) { result =>
        expect(result.exists(_.comment === newComment))
      }
    }

    "return Updated if comment row was already existed" in withJDBC { db =>
      val accountNo     = "123-456"
      val transactionId = "0"
      val newComment    = "updated"
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
        .createOrUpdate(AccountNo(accountNo), TransactionId(transactionId), Comment(newComment), tenant)
        .futureValue
      expect(actual === CreateOrUpdateCommentResult.Updated)

      db.validate(CommentStore.filter(_.commentId === transactionId).result) { result =>
        expect(result.headOption.exists(_.comment === newComment))
      }
    }

    "return TransactionNotFound if transaction was not found" in withJDBC { _ =>
      val actual =
        service.createOrUpdate(AccountNo("123-456"), TransactionId("0"), Comment("update"), tenant).futureValue
      expect(actual === CreateOrUpdateCommentResult.TransactionNotFound)
    }
  }
}
