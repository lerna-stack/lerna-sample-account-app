package myapp.presentation.application

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ HttpHeader, StatusCodes }
import akka.http.scaladsl.server.{ MalformedHeaderRejection, MissingHeaderRejection }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import lerna.testkit.airframe.DISessionSupport
import myapp.adapter.account.BankAccountApplication.{
  DepositResult,
  FetchBalanceResult,
  RefundResult,
  WithdrawalResult,
}
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionDto, TransactionId }
import myapp.adapter.query.ReadTransactionRepository
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
    .bind[ReadTransactionRepository].toInstance(mock[ReadTransactionRepository])
    .bind[ActorSystem[Nothing]].toInstance(system.toTyped)
  val route: ApplicationRoute = diSession.build[ApplicationRoute]

  private val accountService: BankAccountApplication = diSession.build[BankAccountApplication]
  private val fetchBalance: MockFunction2[AccountNo, AppRequestContext, Future[FetchBalanceResult]] =
    accountService.fetchBalance(_)(_)
  private val deposit: MockFunction4[AccountNo, TransactionId, BigInt, AppRequestContext, Future[DepositResult]] =
    accountService.deposit(_, _, _)(_)
  private val withdraw: MockFunction4[AccountNo, TransactionId, BigInt, AppRequestContext, Future[WithdrawalResult]] =
    accountService.withdraw(_, _, _)(_)
  private val refund: MockFunction5[
    AccountNo,
    TransactionId,
    TransactionId,
    BigInt,
    AppRequestContext,
    Future[RefundResult],
  ] = accountService.refund(_, _, _, _)(_)

  private val readTransactionRepository = diSession.build[ReadTransactionRepository]
  private val getTransactionList: MockFunction4[AccountNo, AppTenant, Int, Int, Future[Seq[TransactionDto]]] =
    readTransactionRepository.getTransactionList(_, _, _, _)

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
        }).returns(Future.successful(FetchBalanceResult.Succeeded(100)))

      fetchBalance
        .expects(where { (accountNo, context) =>
          accountNo === AccountNo("123-456") &&
          context.tenant === TenantB
        }).returns(Future.successful(FetchBalanceResult.Succeeded(200)))

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

    "return ServiceUnavailable if the account replies with a timeout against the fetch balance request" in {

      fetchBalance
        .expects(where { (accountNo, context) =>
          accountNo === AccountNo("123-456") &&
          context.tenant === TenantA
        })
        .returns(Future.successful(FetchBalanceResult.Timeout))

      Get("/accounts/123-456").withHeaders(tenantHeader(TenantA)) ~> route.route ~> check {
        expect(status === StatusCodes.ServiceUnavailable)
      }

    }

    "deposit the given amount into the account and then return the account balance" in {

      deposit
        .expects(where { (accountNo, _, _, context) =>
          accountNo === AccountNo("123-456") &&
          context.tenant === TenantA
        }).onCall { (_, _, amount, _) =>
          Future.successful(DepositResult.Succeeded(100 + amount))
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
          Future.successful(DepositResult.Succeeded(200 + amount))
        }

      Post("/accounts/123-456/deposit?transactionId=deposit1&amount=300")
        .withHeaders(tenantHeader(TenantB)) ~> route.route ~> check {
        expect(status === StatusCodes.OK)
        expect(responseAs[String] === "500\n")
      }

    }

    "not deposit the given amount due to an excess balance" in {

      deposit
        .expects(where { (accountNo, _, _, context) =>
          accountNo === AccountNo("123-456") &&
          context.tenant === TenantB
        }).returns(Future.successful(DepositResult.ExcessBalance))

      Post("/accounts/123-456/deposit?transactionId=deposit1&amount=100")
        .withHeaders(tenantHeader(TenantB)) ~> route.route ~> check {
        expect(status === StatusCodes.BadRequest)
        expect(responseAs[String] === "Excess Balance\n")
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

    "return ServiceUnavailable if the account replies with a timeout against the deposit request" in {

      deposit
        .expects(where { (accountNo, _, _, context) =>
          accountNo === AccountNo("123-456") &&
          context.tenant === TenantA
        }).returns(Future.successful(DepositResult.Timeout))

      Post("/accounts/123-456/deposit?transactionId=deposit1&amount=100")
        .withHeaders(tenantHeader(TenantA)) ~> route.route ~> check {
        expect(status === StatusCodes.ServiceUnavailable)
      }

    }

    "withdraw the given amount from the account and then return the account balance" in {

      withdraw
        .expects(where { (accountNo, _, amount, context) =>
          accountNo === AccountNo("123-456") &&
          context.tenant === TenantA &&
          amount === BigInt(40)
        }).returns(Future.successful(WithdrawalResult.Succeeded(60)))

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
        }).returns(Future.successful(WithdrawalResult.Succeeded(80)))

      Post("/accounts/123-456/withdraw?transactionId=withdraw1&amount=120")
        .withHeaders(tenantHeader(TenantB)) ~> route.route ~> check {
        expect(status === StatusCodes.OK)
        expect(responseAs[String] === "80\n")
      }

    }

    "not withdraw the given amount due to a short balance" in {

      withdraw
        .expects(where { (accountNo, _, _, context) =>
          accountNo === AccountNo("123-456") &&
          context.tenant === TenantA
        }).returns(Future.successful(WithdrawalResult.ShortBalance))

      Post("/accounts/123-456/withdraw?transactionId=withdraw1&amount=40")
        .withHeaders(tenantHeader(TenantA)) ~> route.route ~> check {
        expect(status === StatusCodes.BadRequest)
        expect(responseAs[String] === "Short Balance\n")
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

    "return ServiceUnavailable if the account replies with a timeout against the withdrawal request" in {

      withdraw
        .expects(where { (accountNo, _, _, context) =>
          accountNo === AccountNo("123-456") &&
          context.tenant === TenantA
        }).returns(Future.successful(WithdrawalResult.Timeout))

      Post("/accounts/123-456/withdraw?transactionId=withdraw1&amount=40")
        .withHeaders(tenantHeader(TenantA)) ~> route.route ~> check {
        expect(status === StatusCodes.ServiceUnavailable)
      }
    }

    "refund the given amount into the account and then return the account balance" in {

      refund
        .expects(where { (accountNo, transactionId, withdrawalTransactionId, amount, context) =>
          accountNo === AccountNo("123-456") &&
          context.tenant === TenantA &&
          transactionId === TransactionId("refund1") &&
          withdrawalTransactionId === TransactionId("withdrawal1") &&
          amount === BigInt(100)
        }).returns(Future.successful(RefundResult.Succeeded(400)))

      Put("/accounts/123-456/refund?transactionId=refund1&withdrawalTransactionId=withdrawal1&amount=100")
        .withHeaders(tenantHeader(TenantA)) ~> route.route ~> check {
        expect(status === StatusCodes.OK)
        expect(responseAs[String] === "400\n")
      }

    }

    "not refund due to an invalid argument" in {

      refund
        .expects(where { (accountNo, transactionId, withdrawalTransactionId, amount, context) =>
          accountNo === AccountNo("123-456") &&
          context.tenant === TenantA &&
          transactionId === TransactionId("refund1") &&
          withdrawalTransactionId === TransactionId("withdrawal1") &&
          amount === BigInt(100)
        }).returns(Future.successful(RefundResult.InvalidArgument))

      Put("/accounts/123-456/refund?transactionId=refund1&withdrawalTransactionId=withdrawal1&amount=100")
        .withHeaders(tenantHeader(TenantA)) ~> route.route ~> check {
        expect(status === StatusCodes.BadRequest)
      }

    }

    "return ServiceUnavailable if the account replies with a timeout against the refund request" in {

      refund
        .expects(where { (accountNo, transactionId, withdrawalTransactionId, amount, context) =>
          accountNo === AccountNo("123-456") &&
          context.tenant === TenantA &&
          transactionId === TransactionId("refund1") &&
          withdrawalTransactionId === TransactionId("withdrawal1") &&
          amount === BigInt(100)
        }).returns(Future.successful(RefundResult.Timeout))

      Put("/accounts/123-456/refund?transactionId=refund1&withdrawalTransactionId=withdrawal1&amount=100")
        .withHeaders(tenantHeader(TenantA)) ~> route.route ~> check {
        expect(status === StatusCodes.ServiceUnavailable)
      }

    }

    "return the account statement of the given account" in {

      getTransactionList
        .expects(where { (accountNo, tenant, _, _) =>
          accountNo === AccountNo("123-456")
          tenant === TenantA
        }).returns(
          Future.successful(
            Seq(
              TransactionDto(
                "transactionId",
                "Deposited",
                1000,
                10000,
                1637285782,
              ),
            ),
          ),
        )

      getTransactionList
        .expects(where { (accountNo, tenant, offset, limit) =>
          accountNo === AccountNo("123-456")
          tenant === TenantB
          offset === 10
          limit === 1
        }).returns(
          Future.successful(
            Seq(
              TransactionDto(
                "transactionId",
                "Withdrew",
                1000,
                9000,
                1637812723,
              ),
            ),
          ),
        )

      import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
      Get("/accounts/123-456/transactions").withHeaders(tenantHeader(TenantA)) ~> route.route ~> check {
        expect(status === StatusCodes.OK)
        expect(
          responseAs[AccountStatementResponse] === AccountStatementResponse.from(
            AccountNo("123-456"),
            TenantA,
            Seq(TransactionDto("transactionId", "Deposited", 1000, 10000, 1637285782)),
          ),
        )
      }

      Get("/accounts/123-456/transactions?offset=10&limit=1").withHeaders(
        tenantHeader(TenantB),
      ) ~> route.route ~> check {
        expect(status === StatusCodes.OK)
        expect(
          responseAs[AccountStatementResponse] === AccountStatementResponse.from(
            AccountNo("123-456"),
            TenantB,
            Seq(TransactionDto("transactionId", "Withdrew", 1000, 9000, 1637812723)),
          ),
        )
      }
    }
  }

}
