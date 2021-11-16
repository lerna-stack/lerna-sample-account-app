package myapp.application.projection.transaction

import akka.Done
import akka.actor.typed.ActorSystem
import myapp.readmodel.schema.Tables
import slick.dbio.DBIO

trait TransactionRepository {
  def save(transaction: Transaction): DBIO[Done]
}

class TransactionRepositoryImpl(tables: Tables, system: ActorSystem[Nothing]) extends TransactionRepository {
  import system.executionContext
  import tables._
  import tables.profile.api._
  override def save(transaction: Transaction): slick.dbio.DBIO[Done] = {
    (TransactionStore += TransactionStoreRow(
      transaction.transactionId.value,
      transaction.eventType.toString,
      transaction.amount.longValue,
    ))
      .map(_ => Done)
  }
}
