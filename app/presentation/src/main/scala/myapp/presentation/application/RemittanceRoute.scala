package myapp.presentation.application

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import myapp.adapter.account.{ AccountNo, RemittanceApplication }
import myapp.presentation.util.directives.AppRequestContextAndLogging._

final class RemittanceRoute(remittanceApplication: RemittanceApplication) {

  import RemittanceApplication._
  import RemittanceRoute._

  def route: Route = {
    val remittanceParameters =
      parameters("sourceAccountNo".as[AccountNo], "destinationAccountNo".as[AccountNo], "amount".as[Int])
    withAppRequestContextAndLogging { implicit appRequestContext =>
      (pathPrefix("remittance" / Segment.map(RemittanceTransactionId)) & put & remittanceParameters) {
        (transactionId, sourceAccountNo, destinationAccountNo, amount) =>
          val remittance =
            remittanceApplication.remit(sourceAccountNo, destinationAccountNo, transactionId, amount)
          onSuccess(remittance) {
            case RemitResult.Succeeded =>
              complete(StatusCodes.OK)
            case RemitResult.InvalidArgument =>
              complete(StatusCodes.BadRequest, "InvalidParameter(s)\n")
            case RemitResult.ShortBalance =>
              complete(StatusCodes.BadRequest, "ShortBalance\n")
            case RemitResult.ExcessBalance =>
              complete(StatusCodes.BadRequest, "ExcessBalance\n")
            case RemitResult.Timeout =>
              complete(StatusCodes.ServiceUnavailable)
          }
      }
    }
  }

}

object RemittanceRoute {
  import akka.http.scaladsl.unmarshalling.Unmarshaller

  implicit val accountNoFromStringUnmarshaller: Unmarshaller[String, AccountNo] = {
    implicitly[Unmarshaller[String, String]].map(AccountNo.apply)
  }

}
