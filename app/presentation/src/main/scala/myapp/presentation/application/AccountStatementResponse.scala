package myapp.presentation.application

import lerna.http.json.AnyValJsonFormat
import myapp.adapter.account.{ AccountNo, TransactionDto }
import spray.json.{ JsonFormat, RootJsonFormat }

final case class AccountStatementResponse(
    accountNo: AccountNo,
    transactions: Seq[TransactionDto],
)

object AccountStatementResponse {
  def from(accountNo: AccountNo, transactionList: Seq[TransactionDto]): AccountStatementResponse =
    AccountStatementResponse(accountNo, transactionList)

  import spray.json.DefaultJsonProtocol._

  implicit private val accountNoJsonFormat: JsonFormat[AccountNo]        = AnyValJsonFormat(AccountNo.apply, AccountNo.unapply)
  implicit private val transactionJsonFormat: JsonFormat[TransactionDto] = jsonFormat5(TransactionDto)
  implicit val accountStatementJsonFormat: RootJsonFormat[AccountStatementResponse] = jsonFormat2(
    AccountStatementResponse.apply,
  )
}
