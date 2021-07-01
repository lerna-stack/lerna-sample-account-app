package myapp.presentation.application

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionId }

class ApplicationRoute(bankAccountApplication: BankAccountApplication) {
  import ApplicationRoute._

  def route: Route = concat(
    path("index") {
      complete(StatusCodes.OK -> "OK")
    },
    pathPrefix("accounts" / Segment.map(AccountNo)) { accountNo =>
      concat(
        get {
          onSuccess(bankAccountApplication.fetchBalance(accountNo)) { result =>
            complete(result.toString + "\n")
          }
        },
        (post & parameters("amount".as[Int], "transactionId".asTransactionId)) { (amount, transactionId) =>
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
    },
  )
}

object ApplicationRoute {
  implicit class NameUnmarshallerReceptacleOps(name: String) {
    import akka.http.scaladsl.common.NameUnmarshallerReceptacle
    import akka.http.scaladsl.unmarshalling.Unmarshaller

    def asTransactionId: NameUnmarshallerReceptacle[TransactionId] =
      name.as[Long].as[TransactionId](Unmarshaller.strict(TransactionId.apply))
  }
}
