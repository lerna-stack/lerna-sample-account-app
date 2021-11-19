package myapp.presentation.application

import lerna.http.json.AnyValJsonFormat
import myapp.adapter.account.{ AccountNo, TransactionDto }
import spray.json.{ JsonFormat, RootJsonFormat }

final case class AccountStatementResponse(
    accountNo: AccountNo,
    transactions: List[TransactionDto],
)

object AccountStatementResponse {
  import spray.json.DefaultJsonProtocol._

  implicit private val accountNoJsonFormat: JsonFormat[AccountNo]        = AnyValJsonFormat(AccountNo.apply, AccountNo.unapply)
  implicit private val transactionJsonFormat: JsonFormat[TransactionDto] = jsonFormat5(TransactionDto)
  implicit val accountStatementJsonFormat: RootJsonFormat[AccountStatementResponse] = jsonFormat2(
    AccountStatementResponse.apply,
  )
}
