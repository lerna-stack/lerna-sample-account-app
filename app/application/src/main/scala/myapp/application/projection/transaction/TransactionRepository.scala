package myapp.application.projection.transaction

import akka.Done
import myapp.readmodel.schema.Tables
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

trait TransactionRepository {
  def save(transaction: Transaction)(implicit ec: ExecutionContext): DBIO[Done]
}

class TransactionRepositoryImpl(tables: Tables) extends TransactionRepository {
  import tables._
  import tables.profile.api._
  override def save(transaction: Transaction)(implicit ec: ExecutionContext): slick.dbio.DBIO[Done] = {
    (TransactionStore += TransactionStoreRow(
      transaction.transactionId.value,
      transaction.eventType.toString,
      transaction.amount.longValue,
    ))
      .map(_ => Done)
  }
}
