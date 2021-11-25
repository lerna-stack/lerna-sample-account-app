package myapp.presentation.application

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionDto, TransactionId }
import myapp.adapter.query.ReadTransactionRepository
import myapp.presentation.util.directives.AppRequestContextAndLogging._
import myapp.utility.AppRequestContext

class ApplicationRoute(
    bankAccountApplication: BankAccountApplication,
    readTransactionRepository: ReadTransactionRepository,
    system: ActorSystem[Nothing],
) {

  import ApplicationRoute._

  def route: Route = concat(
    path("index") {
      complete(StatusCodes.OK -> "OK")
    },
    withAppRequestContextAndLogging { implicit appRequestContext =>
      pathPrefix("accounts" / Segment.map(AccountNo)) { accountNo =>
        AccountRoute(accountNo)
      }
    },
  )

  object AccountRoute {
    import BankAccountApplication._
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

    def apply(accountNo: AccountNo)(implicit appRequestContext: AppRequestContext): Route = {
      concat(
        getAccountStatementRoute(accountNo),
        fetchBalanceRoute(accountNo),
        (post & parameters("amount".as[Int], "transactionId".as[TransactionId])) { (amount, transactionId) =>
          concat(
            depositRoute(accountNo, transactionId, amount),
            withdrawRoute(accountNo, transactionId, amount),
          )
        },
        refundRoute(accountNo),
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

    private def getAccountStatementRoute(accountNo: AccountNo)(implicit appRequestContext: AppRequestContext): Route = {
      (path("transactions") & get & parameters("offset".as[Int].optional, "limit".as[Int].optional)) {
        (_offset, _limit) =>
          val offset = _offset.getOrElse(0)
          val limit  = _limit.getOrElse(100)
          val futureRepositoryResponse =
            readTransactionRepository.getTransactionList(accountNo, appRequestContext.tenant, offset, limit)
          val futureResponse =
            futureRepositoryResponse.map((transactionList: Seq[TransactionDto]) =>
              AccountStatementResponse.from(accountNo, appRequestContext.tenant, transactionList),
            )(system.executionContext)
          onSuccess(futureResponse) { response =>
            complete(StatusCodes.OK -> response)
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

    // 動作確認のために返金機能を HTTP で公開する。
    // 出金の取引ID等の検証は実施されないため、信頼できるクライアントからのアクセスのみを想定している。
    private def refundRoute(accountNo: AccountNo)(implicit appRequestContext: AppRequestContext): Route = {
      val refundParameters = parameters(
        "transactionId".as[TransactionId],
        "withdrawalTransactionId".as[TransactionId],
        "amount".as[Int],
      )
      (path("refund") & put & refundParameters) { (transactionId, withdrawalTransactionId, amount) =>
        onSuccess(bankAccountApplication.refund(accountNo, transactionId, withdrawalTransactionId, amount)) {
          case RefundResult.Succeeded(balance) =>
            complete(balance.toString + "\n")
          case RefundResult.InvalidArgument =>
            complete(StatusCodes.BadRequest)
          case RefundResult.Timeout =>
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
