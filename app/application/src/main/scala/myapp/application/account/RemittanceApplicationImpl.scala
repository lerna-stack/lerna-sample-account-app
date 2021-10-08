package myapp.application.account

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.scaladsl.{ ClusterSharding, EntityRef }
import akka.util.Timeout
import lerna.akka.entityreplication.util.AtLeastOnceComplete
import lerna.log.AppLogging
import myapp.adapter.account.{ AccountNo, BankAccountApplication, RemittanceApplication }
import myapp.utility.AppRequestContext
import myapp.utility.tenant.AppTenant

import scala.concurrent.duration.{ DurationInt, FiniteDuration }
import scala.concurrent.{ ExecutionContext, Future, TimeoutException }

final class RemittanceApplicationImpl(
    system: ActorSystem[Nothing],
    bankAccountApplication: BankAccountApplication,
) extends RemittanceApplication
    with AppLogging {

  import RemittanceApplication._

  private val clusterSharding                             = ClusterSharding(system)
  private implicit val executionContext: ExecutionContext = system.executionContext
  private implicit val timeout: Timeout                   = 10.seconds
  private val retryInterval: FiniteDuration               = 300.milliseconds

  AppTenant.values.foreach { tenant =>
    val clusterShardingSettings      = clusterShardingSettingsFor(tenant)
    val orchestratorBehaviorSettings = orchestratorBehaviorSettingsFor(tenant)
    val entity =
      RemittanceOrchestratorBehavior
        .entity(tenant, orchestratorBehaviorSettings, bankAccountApplication)
        .withSettings(clusterShardingSettings)
    clusterSharding.init(entity)
  }

  /** RemittanceOrchestratorBehavior を実行する Cluster Sharding の設定
    *
    * 送金取引を行う Entity ([[RemittanceOrchestratorBehavior]] ) は 自ら停止しない限り、常に起動し続ける必要がある。
    * Entity が自ら停止するのは、送金取引が完了(成功もしくは失敗)した場合のみである。
    * リバランスや Entity のクラッシュが発生した場合にEntity を自動的に再起動する必要があるため、Remember Entities を使用する。
    *
    * クラスタ全体を再起動した場合にも、Entity を再度起動しなければならない。
    * これを実現するため、Remember Entities の状態を永続化しておく必要もある。
    *
    * これらの仕様は設定ファイルによって変更されるべきではないため、プログラマブルに設定を行う。
    *
    * @see [[https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html#remembering-entities Remembering Entities]]
    */
  private def clusterShardingSettingsFor(tenant: AppTenant): ClusterShardingSettings = {
    ClusterShardingSettings(system)
      .withRememberEntities(true)
      .withRememberEntitiesStoreMode(ClusterShardingSettings.RememberEntitiesStoreModeEventSourced)
      .withJournalPluginId(
        s"myapp.application.remittance-orchestrator.sharding-state-persistence-${tenant.id}.journal",
      )
      .withSnapshotPluginId(
        s"myapp.application.remittance-orchestrator.sharding-state-persistence-${tenant.id}.snapshot",
      )
  }

  private def orchestratorBehaviorSettingsFor(tenant: AppTenant): RemittanceOrchestratorBehavior.Settings = {
    RemittanceOrchestratorBehavior.Settings(
      s"myapp.application.remittance-orchestrator.persistence-${tenant.id}.journal",
      s"myapp.application.remittance-orchestrator.persistence-${tenant.id}.snapshot",
      withdrawalRetryDelay = 500.millis,
      depositRetryDelay = 500.millis,
      refundRetryDelay = 500.millis,
      passivateTimeout = 120.seconds,
      persistenceFailureRestartMinBackOff = 10.seconds,
      persistenceFailureRestartMaxBackOff = 30.seconds,
      persistenceFailureRestartRandomFactor = 0.2,
    )
  }

  /** 送金取引 Entity を取得する
    *
    * 送金取引 Entity はテナントごとに 送金取引ID により一意に識別できる。
    */
  private def entityRefFor(
      tenant: AppTenant,
      transactionId: RemittanceTransactionId,
  ): EntityRef[RemittanceOrchestratorBehavior.Command] = {
    val typeKey = RemittanceOrchestratorBehavior.typeKey(tenant)
    clusterSharding.entityRefFor(typeKey, transactionId.value)
  }

  /** @inheritdoc */
  override def remit(
      sourceAccountNo: AccountNo,
      destinationAccountNo: AccountNo,
      transactionId: RemittanceTransactionId,
      amount: BigInt,
  )(implicit appRequestContext: AppRequestContext): Future[RemitResult] = {
    implicit val sys: ActorSystem[_] = system
    val entityRef                    = entityRefFor(appRequestContext.tenant, transactionId)
    val remitCommand                 = RemittanceOrchestratorBehavior.Remit(sourceAccountNo, destinationAccountNo, amount, _)
    AtLeastOnceComplete
      .askTo(entityRef, remitCommand, retryInterval)
      .map {
        case RemittanceOrchestratorBehavior.RemitSucceeded =>
          RemitResult.Succeeded
        case RemittanceOrchestratorBehavior.InvalidArgument =>
          RemitResult.InvalidArgument
        case RemittanceOrchestratorBehavior.ShortBalance =>
          RemitResult.ShortBalance
        case RemittanceOrchestratorBehavior.ExcessBalance =>
          RemitResult.ExcessBalance
      }
      .recover {
        case cause: TimeoutException =>
          logger.warn(cause, "Could not get a response from the entity in the timeout({})", timeout)
          RemitResult.Timeout
      }
  }

}
