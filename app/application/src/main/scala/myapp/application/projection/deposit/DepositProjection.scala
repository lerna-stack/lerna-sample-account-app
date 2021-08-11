package myapp.application.projection.deposit

import akka.Done
import akka.actor.typed.{ ActorSystem, Behavior }
import akka.projection.slick.SlickProjection
import akka.projection.{ Projection, ProjectionBehavior, ProjectionContext, ProjectionId }
import akka.stream.scaladsl.FlowWithContext
import akka.util.Timeout
import lerna.akka.entityreplication.typed.ClusterReplication
import lerna.util.trace.TraceId
import myapp.adapter.account.TransactionId
import myapp.application.account.BankAccountBehavior
import myapp.application.projection.AppEventHandler.BehaviorSetup
import myapp.utility.AppRequestContext
import slick.jdbc.JdbcProfile

import scala.concurrent.duration._

private[deposit] object DepositProjection {

  /** RDBMS に永続化する `Offset` の型
    * 選択できる型は次のページを参照: https://doc.akka.io/docs/akka-projection/1.2.1/slick.html#offset-types
    */
  type Offset = Long
}

class DepositProjection(sourceProvider: DepositSourceProvider, config: DepositProjectionConfig)(implicit
    system: ActorSystem[Nothing],
) {

  private[this] val replication = ClusterReplication(system)

  def createProjection(setup: BehaviorSetup): Projection[Deposit] = {
    val projectionId = ProjectionId("DepositProjection", setup.tenant.id)
    val flow =
      FlowWithContext[Deposit, ProjectionContext]
        .throttle(config.maxCommandThroughputPerSec, per = 1.seconds)
        .mapAsync[BankAccountBehavior.DepositSucceeded](config.commandParallelism) { d =>
          // コマンド単位でユニークになる TraceId を発行する
          val traceId = TraceId(s"${projectionId.id}:${d.depositId.value.toString}")

          implicit val timeout: Timeout           = config.commandTimeout
          implicit val context: AppRequestContext = AppRequestContext(traceId, setup.tenant)
          val entityRef                           = replication.entityRefFor(BankAccountBehavior.typeKey, d.accountNo)
          // 取引全体でユニークになるような TransactionId を発行する
          val transactionId = TransactionId(s"${projectionId.id}:${d.depositId.value.toString}")
          entityRef ? (BankAccountBehavior.Deposit(transactionId, d.amount, _))
        }
        .map { _ => Done }

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
