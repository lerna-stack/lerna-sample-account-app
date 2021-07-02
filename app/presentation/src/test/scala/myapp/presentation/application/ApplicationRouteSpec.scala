package myapp.presentation.application

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import lerna.testkit.airframe.DISessionSupport
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionId }
import myapp.presentation.PresentationDIDesign
import myapp.utility.AppRequestContext
import myapp.utility.scalatest.StandardSpec
import wvlet.airframe.Design

import scala.concurrent.Future

@SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
class ApplicationRouteSpec extends StandardSpec with ScalatestRouteTest with DISessionSupport {
  override protected val diDesign: Design = PresentationDIDesign.presentationDesign
    .bind[BankAccountApplication].toInstance(new BankAccountApplication {
      override def fetchBalance(
          accountNo: AccountNo,
      )(implicit appRequestContext: AppRequestContext): Future[BigInt] = ???
      override def deposit(
          accountNo: AccountNo,
          transactionId: TransactionId,
          amount: Int,
      )(implicit appRequestContext: AppRequestContext): Future[BigInt] = ???
      override def withdraw(
          accountNo: AccountNo,
          transactionId: TransactionId,
          amount: Int,
      )(implicit appRequestContext: AppRequestContext): Future[BigInt] = ???
    })

  val route: ApplicationRoute = diSession.build[ApplicationRoute]

  "ApplicationRoute" should {

    "reply OK" in {
      Get("/index") ~> route.route ~> check {
        expect {
          status === StatusCodes.OK
          responseAs[String] === "OK"
        }
      }
    }

  }

}
