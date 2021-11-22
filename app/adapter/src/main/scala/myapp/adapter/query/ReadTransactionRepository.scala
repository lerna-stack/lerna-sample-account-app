package myapp.adapter.query

import myapp.adapter.account.{AccountNo, TransactionDto}

import scala.concurrent.Future

trait ReadTransactionRepository {
  def getTransactionList(accountNo: AccountNo): Future[Seq[TransactionDto]]
}
