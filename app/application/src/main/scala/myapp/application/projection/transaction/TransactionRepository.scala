package myapp.application.projection.transaction

import akka.Done
import slick.basic.DatabaseConfig
import slick.dbio.DBIO
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

case class Transaction(transactionId: String, eventName: String, amount: Int)

trait TransactionRepository {
  def save(transaction: Transaction)(implicit ec: ExecutionContext): DBIO[Done]
}

class TransactionRepositoryImpl(val dbConfig: DatabaseConfig[JdbcProfile]) extends TransactionRepository {

  import dbConfig.profile.api._

  private val transactionsTable = TableQuery[TransactionsTable]

  override def save(transaction: Transaction)(implicit ec: ExecutionContext) = {
    transactionsTable.insertOrUpdate(transaction).map(_ => Done)
  }

  def createTable(): Future[Unit] = dbConfig.db.run(transactionsTable.schema.createIfNotExists)

  private class TransactionsTable(tag: Tag) extends Table[Transaction](tag, "TRANSACTIONS") {
    override def * = (transactionId, eventName, amount).mapTo[Transaction]

    def transactionId = column[String]("TRANSACTION_ID", O.PrimaryKey)

    def eventName = column[String]("EVENT_NAME")

    def amount = column[Int]("AMOUNT")
  }
}

