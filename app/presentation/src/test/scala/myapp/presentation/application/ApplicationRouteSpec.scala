package myapp.presentation.application

import akka.http.scaladsl.model.{ HttpHeader, StatusCodes }
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.{ MalformedHeaderRejection, MissingHeaderRejection }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import lerna.testkit.airframe.DISessionSupport
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionId }
import myapp.presentation.PresentationDIDesign
import myapp.utility.AppRequestContext
import myapp.utility.scalatest.StandardSpec
import myapp.utility.tenant.{ AppTenant, TenantA, TenantB }
import org.scalamock.function._
import org.scalamock.scalatest.MockFactory
import wvlet.airframe.Design

import scala.concurrent.Future

@SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride", "org.wartremover.warts.IsInstanceOf"))
class ApplicationRouteSpec extends StandardSpec with ScalatestRouteTest with MockFactory with DISessionSupport {
  override protected val diDesign: Design = PresentationDIDesign.presentationDesign
    .bind[BankAccountApplication].toInstance(mock[BankAccountApplication])

  val route: ApplicationRoute = diSession.build[ApplicationRoute]

  private val accountService: BankAccountApplication = diSession.build[BankAccountApplication]
  private val fetchBalance: MockFunction2[AccountNo, AppRequestContext, Future[BigInt]] =
    accountService.fetchBalance(_)(_)
  private val deposit: MockFunction4[AccountNo, TransactionId, BigInt, AppRequestContext, Future[BigInt]] =
    accountService.deposit(_, _, _)(_)
  private val withdraw: MockFunction4[AccountNo, TransactionId, BigInt, AppRequestContext, Future[BigInt]] =
    accountService.withdraw(_, _, _)(_)

  private val invalidTenant = new AppTenant {
    override def id: String = "invalid"
  }
  private def tenantHeader(tenant: AppTenant): HttpHeader = {
    RawHeader("X-Tenant-Id", tenant.id)
  }

  "ApplicationRoute" should {

    "reply OK" in {
      Get("/index") ~> route.route ~> check {
        expect {
          status === StatusCodes.OK
          responseAs[String] === "OK"
        }
      }
    }

    "return the account balance of the given account" in {

      fetchBalance
        .expects(where { (accountNo, context) =>
          accountNo === AccountNo("123-456") &&
          context.tenant === TenantA
        }).returns(Future.successful(100))

      fetchBalance
        .expects(where { (accountNo, context) =>
          accountNo === AccountNo("123-456") &&
          context.tenant === TenantB
        }).returns(Future.successful(200))

      Get("/accounts/123-456").withHeaders(tenantHeader(TenantA)) ~> route.route ~> check {
        expect(status === StatusCodes.OK)
        expect(responseAs[String] === "100\n")
      }

      Get("/accounts/123-456").withHeaders(tenantHeader(TenantB)) ~> route.route ~> check {
        expect(status === StatusCodes.OK)
        expect(responseAs[String] === "200\n")
      }

    }

    "not return the account balance with an invalid tenant" in {

      Get("/accounts/123-456").withHeaders(tenantHeader(invalidTenant)) ~> route.route ~> check {
        expect(rejection.isInstanceOf[MalformedHeaderRejection])
      }

    }

    "not return the account balance without a tenant" in {

      Get("/accounts/123-456") ~> route.route ~> check {
        expect(rejection.isInstanceOf[MissingHeaderRejection])
      }

    }

    "deposit the given amount into the account and then return the account balance" in {

      deposit
        .expects(where { (accountNo, _, _, context) =>
          accountNo === AccountNo("123-456") &&
          context.tenant === TenantA
        }).onCall { (_, _, amount, _) =>
          Future.successful(100 + amount)
        }

      Post("/accounts/123-456/deposit?transactionId=deposit1&amount=200")
        .withHeaders(tenantHeader(TenantA)) ~> route.route ~> check {
        expect(status === StatusCodes.OK)
        expect(responseAs[String] === "300\n")
      }

      deposit
        .expects(where { (accountNo, _, _, context) =>
          accountNo === AccountNo("123-456") &&
          context.tenant === TenantB
        }).onCall { (_, _, amount, _) =>
          Future.successful(200 + amount)
        }

      Post("/accounts/123-456/deposit?transactionId=deposit1&amount=300")
        .withHeaders(tenantHeader(TenantB)) ~> route.route ~> check {
        expect(status === StatusCodes.OK)
        expect(responseAs[String] === "500\n")
      }

    }

    "not deposit with an invalid tenant" in {

      Post("/accounts/123-456/deposit?transactionId=deposit1&amount=100")
        .withHeaders(tenantHeader(invalidTenant)) ~> route.route ~> check {
        expect(rejection.isInstanceOf[MalformedHeaderRejection])
      }

    }

    "not deposit without a tenant" in {

      Post("/accounts/123-456/deposit?transactionId=deposit1&amount=100") ~> route.route ~> check {
        expect(rejection.isInstanceOf[MissingHeaderRejection])
      }

    }

    "withdraw the given amount from the account and then return the account balance" in {

      withdraw
        .expects(where { (accountNo, _, amount, context) =>
          accountNo === AccountNo("123-456") &&
          context.tenant === TenantA &&
          amount === BigInt(40)
        }).returns(Future.successful(60))

      Post("/accounts/123-456/withdraw?transactionId=withdraw1&amount=40")
        .withHeaders(tenantHeader(TenantA)) ~> route.route ~> check {
        expect(status === StatusCodes.OK)
        expect(responseAs[String] === "60\n")
      }

      withdraw
        .expects(where { (accountNo, _, ammount, context) =>
          accountNo === AccountNo("123-456") &&
          context.tenant === TenantB &&
          ammount === BigInt(120)
        }).returns(Future.successful(80))

      Post("/accounts/123-456/withdraw?transactionId=withdraw1&amount=120")
        .withHeaders(tenantHeader(TenantB)) ~> route.route ~> check {
        expect(status === StatusCodes.OK)
        expect(responseAs[String] === "80\n")
      }

    }

    "not withdraw with an invalid tenant" in {

      Post("/accounts/123-456/withdraw?transactionId=withdraw1&amount=100")
        .withHeaders(tenantHeader(invalidTenant)) ~> route.route ~> check {
        expect(rejection.isInstanceOf[MalformedHeaderRejection])
      }

    }

    "not withdraw without a tenant" in {

      Post("/accounts/123-456/withdraw?transactionId=withdraw1&amount=100") ~> route.route ~> check {
        expect(rejection.isInstanceOf[MissingHeaderRejection])
      }

    }

  }

}
