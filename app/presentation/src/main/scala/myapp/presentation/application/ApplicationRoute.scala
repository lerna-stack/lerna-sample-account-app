package myapp.presentation.application

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import lerna.http.directives.RequestLogDirective
import myapp.adapter.account.BankAccountApplication.FetchBalanceResult
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionId }
import myapp.presentation.util.directives.AppRequestContextDirective

class ApplicationRoute(bankAccountApplication: BankAccountApplication)
    extends AppRequestContextDirective
    with RequestLogDirective {
  import ApplicationRoute._

  def route: Route = concat(
    path("index") {
      complete(StatusCodes.OK -> "OK")
    },
    extractAppRequestContext { implicit appRequestContext =>
      (logRequestDirective & logRequestResultDirective & pathPrefix("accounts" / Segment.map(AccountNo))) { accountNo =>
        concat(
          get {
            onSuccess(bankAccountApplication.fetchBalance(accountNo)) {
              case FetchBalanceResult.Succeeded(balance) =>
                complete(balance.toString + "\n")
              case FetchBalanceResult.Timeout =>
                complete(StatusCodes.ServiceUnavailable)
            }
          },
          (post & parameters("amount".as[Int], "transactionId".as[TransactionId])) { (amount, transactionId) =>
            import BankAccountApplication._
            concat(
              path("deposit") {
                onSuccess(bankAccountApplication.deposit(accountNo, transactionId, amount)) {
                  case DepositResult.Succeeded(balance) =>
                    complete(balance.toString + "\n")
                  case DepositResult.ExcessBalance =>
                    complete(StatusCodes.BadRequest, "Excess Balance\n")
                  case DepositResult.Timeout =>
                    complete(StatusCodes.ServiceUnavailable)
                }
              },
              path("withdraw") {
                onSuccess(bankAccountApplication.withdraw(accountNo, transactionId, amount)) {
                  case WithdrawalResult.Succeeded(balance) =>
                    complete(balance.toString + "\n")
                  case WithdrawalResult.ShortBalance =>
                    complete(StatusCodes.BadRequest, "Short Balance\n")
                  case WithdrawalResult.Timeout =>
                    complete(StatusCodes.ServiceUnavailable)
                }
              },
            )
          },
        )
      }
    },
  )
}

object ApplicationRoute {
  import akka.http.scaladsl.unmarshalling.Unmarshaller

  implicit val transactionIdFromStringUnmarshaller: Unmarshaller[String, TransactionId] =
    implicitly[Unmarshaller[String, String]].map(TransactionId.apply)
}
