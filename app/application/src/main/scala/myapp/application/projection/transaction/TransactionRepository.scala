package myapp.application.projection.transaction

import akka.Done
import myapp.readmodel.schema.Tables
import slick.basic.DatabaseConfig
import slick.dbio.DBIO
import slick.jdbc.JdbcProfile

import scala.concurrent.ExecutionContext

final case class Transaction(transactionId: String, eventName: String, amount: Int)

trait TransactionRepository {
  def save(transaction: Transaction)(implicit ec: ExecutionContext): DBIO[Done]
}

class TransactionRepositoryImpl(val table: Tables, dbConfig: DatabaseConfig[JdbcProfile])
    extends TransactionRepository {
  import dbConfig.profile.api._
  import table._
  override def save(transaction: Transaction)(implicit ec: ExecutionContext) = {
    TransactionStore
      .insertOrUpdate(TransactionStoreRow(transaction.transactionId, transaction.eventName, transaction.amount))
      .map(_ => Done)
  }
}
