package myapp.presentation.application

import lerna.http.json.AnyValJsonFormat
import myapp.adapter.account.{ AccountNo, TransactionDto }
import myapp.utility.tenant.AppTenant
import spray.json.{ JsonFormat, RootJsonFormat }

final case class AccountStatementResponse(accountNo: AccountNo, tenant: String, transactions: Seq[TransactionDto])

object AccountStatementResponse {
  def from(accountNo: AccountNo, tenant: AppTenant, transactionList: Seq[TransactionDto]): AccountStatementResponse =
    AccountStatementResponse(accountNo, tenant.id, transactionList)

  import spray.json.DefaultJsonProtocol._

  implicit private val accountNoJsonFormat: JsonFormat[AccountNo]        = AnyValJsonFormat(AccountNo.apply, AccountNo.unapply)
  implicit private val transactionJsonFormat: JsonFormat[TransactionDto] = jsonFormat5(TransactionDto)
  implicit val accountStatementJsonFormat: RootJsonFormat[AccountStatementResponse] =
    jsonFormat3((accountNo: AccountNo, tenant: String, transactions: Seq[TransactionDto]) =>
      AccountStatementResponse.apply(accountNo, tenant, transactions),
    )
}
