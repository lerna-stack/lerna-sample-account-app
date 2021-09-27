package myapp.application.projection.deposit

import akka.Done
import akka.actor.typed.{ ActorSystem, Behavior }
import akka.projection.scaladsl.SourceProvider
import akka.projection.slick.SlickProjection
import akka.projection.{ Projection, ProjectionBehavior, ProjectionContext, ProjectionId }
import akka.stream.scaladsl.FlowWithContext
import lerna.log.AppLogging
import lerna.util.trace.TraceId
import myapp.adapter.account.BankAccountApplication.DepositResult
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionId }
import myapp.application.projection.AppEventHandler.BehaviorSetup
import myapp.utility.AppRequestContext
import slick.jdbc.JdbcProfile

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

private[deposit] object DepositProjection {

  /** RDBMS に永続化する `Offset` の型
    * 選択できる型は次のページを参照: https://doc.akka.io/docs/akka-projection/1.2.1/slick.html#offset-types
    */
  type Offset = Long

  /** [[BankAccountApplication]] が利用不可である
    *
    * この例外は [[DepositProjection]] で [[BankAccountApplication]] が利用できない場合に発生する。
    * 具体的には、[[BankAccountApplication.deposit]] が [[BankAccountApplication.DepositResult.Timeout]] を返した場合がある。
    * この例外に遭遇しても retry や restart することで回復が期待できる。
    */
  final class BankAccountApplicationUnavailable(message: String) extends Exception(message)

}

/** [[DepositSourceProvider]] から入金要求を購読し、[[BankAccountApplication]] に入金を行う
  *
  * 入金処理は At-Least Once で実施されるため、入金は冪等な操作でなければならない。
  * 失敗した入金は、エラーログに記録された後、破棄される。
  */
class DepositProjection(
    depositSourceProvider: DepositSourceProvider,
    config: DepositProjectionConfig,
    bankAccount: BankAccountApplication,
)(implicit
    system: ActorSystem[Nothing],
) extends AppLogging {
  import DepositProjection._

  /** 入金要求 [[Deposit]] を処理する [[Projection]] を作成する
    *
    * ハンドラでエラーが発生した場合には、設定 `akka.projection.recovery-strategy` や
    * `akka.projection.restart-backoff` で構成した方法で retry や restart が行われる。
    *
    * @see https://doc.akka.io/docs/akka-projection/1.1.0/error.html#projection-restart
    * @see https://doc.akka.io/docs/akka-projection/current/error.html#projection-restart
    */
  def createProjection(
      setup: BehaviorSetup,
      // テストで SourceProvider を差し替えられるようにするため
      sourceProvider: SourceProvider[Offset, Deposit] = depositSourceProvider,
  ): Projection[Deposit] = {
    val FutureDone   = Future.successful(Done)
    val projectionId = ProjectionId("DepositProjection", setup.tenant.id)
    val flow =
      FlowWithContext[Deposit, ProjectionContext]
        .throttle(config.maxCommandThroughputPerSec, per = 1.seconds)
        .mapAsync(config.commandParallelism) { request =>
          // コマンド単位でユニークになる TraceId を発行する
          val traceId = TraceId(s"${projectionId.id}:${request.depositId.value.toString}")

          implicit val ec: ExecutionContext       = system.executionContext
          implicit val context: AppRequestContext = AppRequestContext(traceId, setup.tenant)
          val accountNo                           = AccountNo(request.accountNo)
          // 取引全体でユニークになるような TransactionId を発行する
          val transactionId = TransactionId(s"${projectionId.id}:${request.depositId.value.toString}")
          bankAccount
            .deposit(accountNo, transactionId, request.amount)
            .flatMap {
              case DepositResult.Succeeded(balance) =>
                logger.info("Deposit succeeded: request={}", request)
                FutureDone
              case DepositResult.ExcessBalance =>
                logger.error("Deposit failed due to an excess balance: request={}", request)
                FutureDone
              case DepositResult.Timeout =>
                val cause = new BankAccountApplicationUnavailable(
                  s"Deposit failed due to the service being unavailable: request=${request.toString}",
                )
                logger.warn(cause.getMessage)
                Future.failed(cause)
            }
        }

    SlickProjection.atLeastOnceFlow[DepositProjection.Offset, Deposit, JdbcProfile](
      projectionId,
      sourceProvider,
      setup.dbConfig,
      handler = flow,
    )
  }

  def createBehavior(setup: BehaviorSetup): Behavior[ProjectionBehavior.Command] = {
    ProjectionBehavior(createProjection(setup))
  }
}
