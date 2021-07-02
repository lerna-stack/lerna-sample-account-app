package myapp.presentation.application

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionId }
import myapp.presentation.util.directives.AppRequestContextDirective

class ApplicationRoute(bankAccountApplication: BankAccountApplication) extends AppRequestContextDirective {
  import ApplicationRoute._

  def route: Route = concat(
    path("index") {
      complete(StatusCodes.OK -> "OK")
    },
    extractAppRequestContext { implicit appRequestContext =>
      pathPrefix("accounts" / Segment.map(AccountNo)) { accountNo =>
        concat(
          get {
            onSuccess(bankAccountApplication.fetchBalance(accountNo)) { result =>
              complete(result.toString + "\n")
            }
          },
          (post & parameters("amount".as[Int], "transactionId".as[TransactionId])) { (amount, transactionId) =>
            concat(
              path("deposit") {
                onSuccess(bankAccountApplication.deposit(accountNo, transactionId, amount)) { result =>
                  complete(result.toString + "\n")
                }
              },
              path("withdraw") {
                onSuccess(bankAccountApplication.withdraw(accountNo, transactionId, amount)) { result =>
                  complete(result.toString + "\n")
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
    implicitly[Unmarshaller[String, Long]].map(TransactionId.apply)
}
