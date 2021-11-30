package myapp.adapter.query

import myapp.adapter.account.{ AccountNo, TransactionDto }
import myapp.utility.tenant.AppTenant

import scala.concurrent.Future

trait ReadTransactionRepository {
  def getTransactionList(accountNo: AccountNo, tenant: AppTenant, offset: Int, limit: Int): Future[Seq[TransactionDto]]
}
