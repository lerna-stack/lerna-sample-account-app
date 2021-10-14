package myapp.presentation.application

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ HttpHeader, StatusCodes }
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import lerna.testkit.airframe.DISessionSupport
import myapp.adapter.account.{ AccountNo, RemittanceApplication }
import myapp.presentation.PresentationDIDesign
import myapp.utility.AppRequestContext
import myapp.utility.scalatest.StandardSpec
import myapp.utility.tenant.{ AppTenant, TenantA }
import org.scalamock.function.MockFunction5
import org.scalamock.scalatest.MockFactory
import wvlet.airframe.Design

import scala.concurrent.Future

@SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
class RemittanceRouteSpec extends StandardSpec with ScalatestRouteTest with MockFactory with DISessionSupport {
  import RemittanceApplication._

  override protected val diDesign: Design = {
    PresentationDIDesign.presentationDesign
      .bind[RemittanceApplication].toInstance(mock[RemittanceApplication])
  }

  private val route: RemittanceRoute =
    diSession.build[RemittanceRoute]
  private val remittanceApplication: RemittanceApplication =
    diSession.build[RemittanceApplication]
  private val remit
      : MockFunction5[AccountNo, AccountNo, RemittanceTransactionId, BigInt, AppRequestContext, Future[RemitResult]] =
    remittanceApplication.remit(_: AccountNo, _: AccountNo, _: RemittanceTransactionId, _: BigInt)(_: AppRequestContext)

  private val defaultTenant = TenantA
  private val invalidTenant = new AppTenant {
    override def id: String = "invalid"
  }
  private def tenantHeader(tenant: AppTenant): HttpHeader = {
    RawHeader("X-Tenant-Id", tenant.id)
  }

  "RemittanceRoute" should {

    "remit the given amount" in {

      remit
        .expects(where { (source, destination, transactionId, amount, context) =>
          source === AccountNo("account1") &&
          destination === AccountNo("account2") &&
          transactionId === RemittanceTransactionId("remittance1") &&
          amount === BigInt(100) &&
          context.tenant === defaultTenant
        })
        .returns(Future.successful(RemitResult.Succeeded))

      val uri = "/remittance/remittance1?sourceAccountNo=account1&destinationAccountNo=account2&amount=100"
      Put(uri).withHeaders(tenantHeader(defaultTenant)) ~> route.route ~> check {
        assert(status === StatusCodes.OK)
      }

    }

    "not remit the given amount due to an invalid argument" in {

      remit
        .expects(where { (source, destination, transactionId, amount, context) =>
          source === AccountNo("account1") &&
          destination === AccountNo("account2") &&
          transactionId === RemittanceTransactionId("remittance1") &&
          amount === BigInt(-1) &&
          context.tenant === defaultTenant
        })
        .returns(Future.successful(RemitResult.InvalidArgument))

      val uri = "/remittance/remittance1?sourceAccountNo=account1&destinationAccountNo=account2&amount=-1"
      Put(uri).withHeaders(tenantHeader(defaultTenant)) ~> route.route ~> check {
        assert(status === StatusCodes.BadRequest)
        assert(responseAs[String] === "InvalidParameter(s)\n")
      }

    }

    "not remit the given amount due to a short balance" in {

      remit
        .expects(where { (source, destination, transactionId, amount, context) =>
          source === AccountNo("account1") &&
          destination === AccountNo("account2") &&
          transactionId === RemittanceTransactionId("remittance1") &&
          amount === BigInt(100) &&
          context.tenant === defaultTenant
        })
        .returns(Future.successful(RemitResult.ShortBalance))

      val uri = "/remittance/remittance1?sourceAccountNo=account1&destinationAccountNo=account2&amount=100"
      Put(uri).withHeaders(tenantHeader(defaultTenant)) ~> route.route ~> check {
        assert(status === StatusCodes.BadRequest)
        assert(responseAs[String] === "ShortBalance\n")
      }

    }

    "not remit the given amount due to an excess balance" in {

      remit
        .expects(where { (source, destination, transactionId, amount, context) =>
          source === AccountNo("account1") &&
          destination === AccountNo("account2") &&
          transactionId === RemittanceTransactionId("remittance1") &&
          amount === BigInt(100) &&
          context.tenant === defaultTenant
        })
        .returns(Future.successful(RemitResult.ExcessBalance))

      val uri = "/remittance/remittance1?sourceAccountNo=account1&destinationAccountNo=account2&amount=100"
      Put(uri).withHeaders(tenantHeader(defaultTenant)) ~> route.route ~> check {
        assert(status === StatusCodes.BadRequest)
        assert(responseAs[String] === "ExcessBalance\n")
      }

    }

    "return ServiceUnavailable if the remittance application replies with a timeout" in {

      remit
        .expects(where { (source, destination, transactionId, amount, context) =>
          source === AccountNo("account1") &&
          destination === AccountNo("account2") &&
          transactionId === RemittanceTransactionId("remittance1") &&
          amount === BigInt(100) &&
          context.tenant === defaultTenant
        })
        .returns(Future.successful(RemitResult.Timeout))

      val uri = "/remittance/remittance1?sourceAccountNo=account1&destinationAccountNo=account2&amount=100"
      Put(uri).withHeaders(tenantHeader(defaultTenant)) ~> route.route ~> check {
        assert(status === StatusCodes.ServiceUnavailable)
      }

    }

    "not remit with an invalid tenant" in {

      val uri = "/remittance/remittance1?sourceAccountNo=account1&destinationAccountNo=account2&amount=100"
      Put(uri).withHeaders(tenantHeader(invalidTenant)) ~> Route.seal(route.route) ~> check {
        expect(status === StatusCodes.BadRequest)
      }

    }

    "not remit without a tenant" in {

      val uri = "/remittance/remittance1?sourceAccountNo=account1&destinationAccountNo=account2&amount=100"
      Put(uri) ~> Route.seal(route.route) ~> check {
        expect(status === StatusCodes.BadRequest)
      }

    }

  }

}
