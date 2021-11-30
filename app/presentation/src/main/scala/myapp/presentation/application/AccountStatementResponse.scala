package myapp.presentation.application

import myapp.adapter.account.{ AccountNo, TransactionDto }
import myapp.utility.tenant.AppTenant
import spray.json.{ JsonFormat, RootJsonFormat }

final case class AccountStatementResponse private (
    accountNo: String,
    tenant: String,
    transactions: Seq[TransactionDto],
)

object AccountStatementResponse {
  def from(accountNo: AccountNo, tenant: AppTenant, transactionList: Seq[TransactionDto]): AccountStatementResponse =
    AccountStatementResponse(accountNo.value, tenant.id, transactionList)

  import spray.json.DefaultJsonProtocol._

  implicit private val transactionJsonFormat: JsonFormat[TransactionDto] = jsonFormat5(TransactionDto)
  implicit val accountStatementJsonFormat: RootJsonFormat[AccountStatementResponse] =
    jsonFormat3(AccountStatementResponse.apply)
}
