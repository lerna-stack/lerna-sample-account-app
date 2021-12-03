package myapp.application.projection.transaction

import akka.Done
import akka.actor.typed.ActorSystem
import myapp.adapter.Comment
import myapp.adapter.account.{ AccountNo, TransactionId }
import myapp.readmodel.schema.Tables
import slick.dbio.DBIO

trait TransactionRepository {
  def save(transaction: Transaction): DBIO[Done]

  def findById(transactionId: TransactionId): DBIO[Option[Transaction]]
}

class TransactionRepositoryImpl(tables: Tables, system: ActorSystem[Nothing]) extends TransactionRepository {
  import system.executionContext
  import tables._
  import tables.profile.api._

  override def save(transaction: Transaction): slick.dbio.DBIO[Done] = (transaction.comment match {
    case None =>
      TransactionStore += TransactionStoreRow(
        transaction.transactionId.value,
        transaction.eventType.toString,
        transaction.accountNo.value,
        transaction.amount.longValue,
        transaction.balance.longValue,
        transaction.transactedAt,
      )
    case Some(comment) =>
      CommentStore.insertOrUpdate(CommentStoreRow(transaction.transactionId.value, comment.value))
  }).map(_ => Done)

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  override def findById(transactionId: TransactionId): slick.dbio.DBIO[Option[Transaction]] = {
    (TransactionStore joinLeft CommentStore)
      .on(_.transactionId === _.commentId)
      .filter(_._1.transactionId === transactionId.value)
      .result
      .headOption
      .map(_.map {
        case (transactionRow, optCommentRow) =>
          Transaction(
            TransactionId(transactionRow.transactionId),
            TransactionEventType.values.find(_.toString == transactionRow.transactionType).get,
            AccountNo(transactionRow.accountNo),
            BigInt(transactionRow.amount),
            BigInt(transactionRow.balance),
            transactionRow.transactedAt,
            optCommentRow.map(cRow => Comment(cRow.comment)),
          )
      })
  }
}
