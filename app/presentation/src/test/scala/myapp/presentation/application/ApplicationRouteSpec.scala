package myapp.presentation.application

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ ContentTypes, HttpHeader, StatusCodes }
import akka.http.scaladsl.server.{ MalformedHeaderRejection, MissingHeaderRejection }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import lerna.testkit.airframe.DISessionSupport
import myapp.adapter.Comment
import myapp.adapter.account.BankAccountApplication.{
  DepositResult,
  FetchBalanceResult,
  RefundResult,
  WithdrawalResult,
}
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionDto, TransactionId }
import myapp.adapter.query.CreateOrUpdateCommentService.CreateOrUpdateCommentResult
import myapp.adapter.query.DeleteCommentService.DeleteCommentResult
import myapp.adapter.query.{ CreateOrUpdateCommentService, DeleteCommentService, ReadTransactionRepository }
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
    .bind[CreateOrUpdateCommentService].toInstance(mock[CreateOrUpdateCommentService])
    .bind[DeleteCommentService].toInstance(mock[DeleteCommentService])
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

  private val createOrUpdateCommentService = diSession.build[CreateOrUpdateCommentService]
  private val createOrUpdateComment
      : MockFunction4[AccountNo, TransactionId, Comment, AppTenant, Future[CreateOrUpdateCommentResult]] =
    createOrUpdateCommentService.createOrUpdate(_, _, _, _)

  private val deleteCommentService = diSession.build[DeleteCommentService]
  private val deleteComment: MockFunction3[AccountNo, TransactionId, AppTenant, Future[DeleteCommentResult]] =
    deleteCommentService.delete(_, _, _)

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
                "test comment 1",
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
                "test comment 2",
              ),
            ),
          ),
        )
      Get("/accounts/123-456/transactions").withHeaders(tenantHeader(TenantA)) ~> route.route ~> check {
        val expectResponseBody =
          """{"accountNo":"123-456","tenant":"tenant-a","transactions":[{"amount":1000,"balance":10000,"comment":"test comment 1","transactedAt":1637285782,"transactionId":"transactionId","transactionType":"Deposited"}]}"""
        expect(status === StatusCodes.OK)
        expect(responseAs[String] === expectResponseBody)
      }

      Get("/accounts/123-456/transactions?offset=10&limit=1").withHeaders(
        tenantHeader(TenantB),
      ) ~> route.route ~> check {
        val expectResponseBody =
          """{"accountNo":"123-456","tenant":"tenant-b","transactions":[{"amount":1000,"balance":9000,"comment":"test comment 2","transactedAt":1637812723,"transactionId":"transactionId","transactionType":"Withdrew"}]}"""
        expect(status === StatusCodes.OK)
        expect(responseAs[String] === expectResponseBody)
      }
    }

    "create or update the comment of the given transaction" in {

      createOrUpdateComment
        .expects(where { (accountNo, transactionId, comment, tenant) =>
          accountNo === AccountNo("123-456")
          transactionId === TransactionId("1638337752")
          comment === Comment("test1")
          tenant === TenantA
        }).returns(
          Future.successful(CreateOrUpdateCommentResult.Created),
        )

      createOrUpdateComment
        .expects(where { (accountNo, transactionId, comment, tenant) =>
          accountNo === AccountNo("123-456")
          transactionId === TransactionId("1638337752")
          comment === Comment("test2")
          tenant === TenantA
        }).returns(
          Future.successful(CreateOrUpdateCommentResult.Updated),
        )

      Put("/accounts/123-456/transactions/1638337752/comment")
        .withEntity(ContentTypes.`application/json`, """{"comment":"test1"}""")
        .withHeaders(tenantHeader(TenantA)) ~> route.route ~> check {
        expect(status === StatusCodes.Created)
      }

      Put("/accounts/123-456/transactions/1638337752/comment")
        .withEntity(ContentTypes.`application/json`, """{"comment":"test2"}""")
        .withHeaders(tenantHeader(TenantA)) ~> route.route ~> check {
        expect(status === StatusCodes.NoContent)
      }
    }

    "return NotFound, if the given transaction which is required comment update is not found" in {
      createOrUpdateComment
        .expects(where { (accountNo, transactionId, comment, tenant) =>
          accountNo === AccountNo("123-456")
          transactionId === TransactionId("1638337752")
          comment === Comment("test1")
          tenant === TenantA
        }).returns(
          Future.successful(CreateOrUpdateCommentResult.TransactionNotFound),
        )
      Put("/accounts/123-456/transactions/1638500554/comment")
        .withEntity(ContentTypes.`application/json`, """{"comment":"test1"}""")
        .withHeaders(tenantHeader(TenantA)) ~> route.route ~> check {
        expect(status === StatusCodes.NotFound)
      }
    }

    "delete comment of the given transaction and return NoContent" in {
      deleteComment
        .expects(where { (accountNo, transactionId, tenant) =>
          accountNo === AccountNo("123-456")
          transactionId === TransactionId("1638337752")
          tenant === TenantA
        }).returns(
          Future.successful(DeleteCommentResult.Deleted),
        )
      Delete("/accounts/123-456/transactions/1638500554/comment")
        .withHeaders(tenantHeader(TenantA)) ~> route.route ~> check {
        expect(status === StatusCodes.NoContent)
      }
    }

    "return NotFound, if the given transaction which is required comment delete is not found" in {
      deleteComment
        .expects(where { (accountNo, transactionId, tenant) =>
          accountNo === AccountNo("123-456")
          transactionId === TransactionId("1638337752")
          tenant === TenantA
        }).returns(
          Future.successful(DeleteCommentResult.TransactionNotFound),
        )
      Delete("/accounts/123-456/transactions/1638500554/comment")
        .withHeaders(tenantHeader(TenantA)) ~> route.route ~> check {
        expect(status === StatusCodes.NotFound)
      }
    }
  }

}
