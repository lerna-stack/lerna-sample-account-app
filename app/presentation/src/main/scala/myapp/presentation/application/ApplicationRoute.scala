package myapp.presentation.application

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ Directive1, Route }
import lerna.http.directives.RequestLogDirective
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionId }
import myapp.presentation.util.directives.AppRequestContextDirective
import myapp.utility.AppRequestContext

class ApplicationRoute(bankAccountApplication: BankAccountApplication)
    extends AppRequestContextDirective
    with RequestLogDirective {
  import ApplicationRoute._

  private val withinContextAndLogging: Directive1[AppRequestContext] = {
    extractAppRequestContext.flatMap { implicit appRequestContext =>
      logRequestDirective & logRequestResultDirective & provide(appRequestContext)
    }
  }

  def route: Route = concat(
    path("index") {
      complete(StatusCodes.OK -> "OK")
    },
    withinContextAndLogging { implicit appRequestContext =>
      pathPrefix("accounts" / Segment.map(AccountNo)) { accountNo =>
        AccountRoute(accountNo)
      }
    },
  )

  object AccountRoute {
    import BankAccountApplication._

    // TODO refactor
    @SuppressWarnings(Array("lerna.warts.CyclomaticComplexity"))
    def apply(accountNo: AccountNo)(implicit appRequestContext: AppRequestContext): Route = {
      concat(
        fetchBalanceRoute(accountNo),
        (post & parameters("amount".as[Int], "transactionId".as[TransactionId])) { (amount, transactionId) =>
          concat(
            depositRoute(accountNo, transactionId, amount),
            withdrawRoute(accountNo, transactionId, amount),
          )
        },
        // 動作確認のために返金機能を HTTP で公開する。
        // 出金の取引ID等の検証は実施されないため、信頼できるクライアントからのアクセスのみを想定している。
        (path("refund") &
        parameters("transactionId".as[TransactionId], "withdrawalTransactionId".as[TransactionId], "amount".as[Int]) &
        put) { (transactionId, withdrawalTransactionId, amount) =>
          onSuccess(
            bankAccountApplication.refund(accountNo, transactionId, withdrawalTransactionId, amount),
          ) {
            case RefundResult.Succeeded(balance) =>
              complete(balance.toString + "\n")
            case RefundResult.InvalidArgument =>
              complete(StatusCodes.BadRequest)
            case RefundResult.Timeout =>
              complete(StatusCodes.ServiceUnavailable)
          }
        },
      )
    }

    private def fetchBalanceRoute(accountNo: AccountNo)(implicit appRequestContext: AppRequestContext): Route = {
      get {
        onSuccess(bankAccountApplication.fetchBalance(accountNo)) {
          case FetchBalanceResult.Succeeded(balance) =>
            complete(balance.toString + "\n")
          case FetchBalanceResult.Timeout =>
            complete(StatusCodes.ServiceUnavailable)
        }
      }
    }

    private def depositRoute(accountNo: AccountNo, transactionId: TransactionId, amount: BigInt)(implicit
        appRequestContext: AppRequestContext,
    ): Route = {
      path("deposit") {
        onSuccess(bankAccountApplication.deposit(accountNo, transactionId, amount)) {
          case DepositResult.Succeeded(balance) =>
            complete(balance.toString + "\n")
          case DepositResult.ExcessBalance =>
            complete(StatusCodes.BadRequest, "Excess Balance\n")
          case DepositResult.Timeout =>
            complete(StatusCodes.ServiceUnavailable)
        }
      }
    }

    private def withdrawRoute(accountNo: AccountNo, transactionId: TransactionId, amount: BigInt)(implicit
        appRequestContext: AppRequestContext,
    ): Route = {
      path("withdraw") {
        onSuccess(bankAccountApplication.withdraw(accountNo, transactionId, amount)) {
          case WithdrawalResult.Succeeded(balance) =>
            complete(balance.toString + "\n")
          case WithdrawalResult.ShortBalance =>
            complete(StatusCodes.BadRequest, "Short Balance\n")
          case WithdrawalResult.Timeout =>
            complete(StatusCodes.ServiceUnavailable)
        }
      }
    }

  }

}

object ApplicationRoute {
  import akka.http.scaladsl.unmarshalling.Unmarshaller

  implicit val transactionIdFromStringUnmarshaller: Unmarshaller[String, TransactionId] =
    implicitly[Unmarshaller[String, String]].map(TransactionId.apply)
}
