package myapp.adapter.query

import myapp.adapter.account.AccountNo
import myapp.utility.tenant.AppTenant

import scala.concurrent.Future

trait GetTransactionListService {
  def getTransactionList(accountNo: AccountNo, tenant: AppTenant, offset: Int, limit: Int): Future[Seq[TransactionDto]]
}
