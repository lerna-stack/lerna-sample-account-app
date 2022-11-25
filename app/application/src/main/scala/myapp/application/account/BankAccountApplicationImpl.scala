package myapp.application.account

import akka.actor.typed.ActorSystem
import akka.util.Timeout
import com.typesafe.config.Config
import lerna.akka.entityreplication.typed._
import lerna.akka.entityreplication.util.AtLeastOnceComplete
import lerna.log.AppLogging
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionId }
import myapp.utility.AppRequestContext
import myapp.utility.tenant.AppTenant

import java.util.concurrent.TimeoutException
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.jdk.CollectionConverters.CollectionHasAsScala

class BankAccountApplicationImpl(root: Config)(implicit system: ActorSystem[Nothing])
    extends BankAccountApplication
    with AppLogging {
  import BankAccountApplication._
  import system.executionContext

  private[this] val replication = ClusterReplication(system)
  AppTenant.values.foreach { implicit tenant =>
    val disableShards = root.getStringList(s"akka-entity-replication.raft.disable-shards-${tenant.id}").asScala.toSet
    val stickyLeaders = {
      val config = root.getConfig(s"akka-entity-replication.raft.sticky-leaders-${tenant.id}")
      val keys   = config.entrySet.asScala.map(_.getKey)
      keys.map(key => key -> config.getString(key)).toMap
    }

    val settings = ClusterReplicationSettings(system)
      .withRaftJournalPluginId(s"akka-entity-replication.raft.persistence.cassandra-${tenant.id}.journal")
      .withRaftSnapshotPluginId(s"akka-entity-replication.raft.persistence.cassandra-${tenant.id}.snapshot")
      .withRaftQueryPluginId(s"akka-entity-replication.raft.persistence.cassandra-${tenant.id}.query")
      .withEventSourcedJournalPluginId(
        s"akka-entity-replication.eventsourced.persistence.cassandra-${tenant.id}.journal",
      )
      .withEventSourcedSnapshotStorePluginId(
        s"akka-entity-replication.eventsourced.persistence.cassandra-${tenant.id}.snapshot",
      )
      .withDisabledShards(disableShards)
      .withStickyLeaders(stickyLeaders)

    val entity = ReplicatedEntity(BankAccountBehavior.typeKey)(context => BankAccountBehavior(context))
      .withSettings(settings)

    replication.init(entity)
  }

  private[this] implicit val timeout: Timeout = Timeout(10.seconds)

  private[this] val retryInterval: FiniteDuration = 300.milliseconds

  private val underMaintenanceShards: Map[AppTenant, Set[String]] = {
    AppTenant.values
      .map(tenant =>
        tenant -> root
          .getConfig("myapp.application.lerna").getStringList(s"under-maintenance-shards-${tenant.id}").asScala.toSet,
      ).toMap
  }

  private def isUnderMaintenance(shardId: String)(implicit tenant: AppTenant) =
    underMaintenanceShards.get(tenant).fold(false)(_.contains(shardId))

  private[this] def entityRef(accountNo: AccountNo)(implicit tenant: AppTenant) =
    replication.entityRefFor(BankAccountBehavior.typeKey, accountNo.value)

  override def fetchBalance(
      accountNo: AccountNo,
  )(implicit appRequestContext: AppRequestContext): Future[FetchBalanceResult] = {
    val shardId = replication.shardIdOf(BankAccountBehavior.typeKey, accountNo.value)
    if (isUnderMaintenance(shardId)) {
      logger.warn("The raft actor(shard id = {}) is under maintenance.", shardId)
      Future.successful(FetchBalanceResult.UnderMaintenance)
    } else {
      AtLeastOnceComplete
        .askTo(entityRef(accountNo), BankAccountBehavior.GetBalance(_), retryInterval)
        .map { accountBalance => FetchBalanceResult.Succeeded(accountBalance.balance) }
        .recover {
          case cause: TimeoutException =>
            logger.warn(cause, "Could not get a response from the entry in the timeout({})", timeout)
            FetchBalanceResult.Timeout
        }
    }
  }

  override def deposit(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: BigInt,
  )(implicit appRequestContext: AppRequestContext): Future[DepositResult] = {
    val shardId = replication.shardIdOf(BankAccountBehavior.typeKey, accountNo.value)
    if (isUnderMaintenance(shardId)) {
      logger.warn("The raft actor(shard id = {}) is under maintenance.", shardId)
      Future.successful(DepositResult.UnderMaintenance)
    } else {
      AtLeastOnceComplete
        .askTo(entityRef(accountNo), BankAccountBehavior.Deposit(transactionId, amount, _), retryInterval)
        .map {
          case BankAccountBehavior.DepositSucceeded(balance) => DepositResult.Succeeded(balance)
          case BankAccountBehavior.ExcessBalance()           => DepositResult.ExcessBalance
        }
        .recover {
          case cause: TimeoutException =>
            logger.warn(cause, "Could not get a response from the entity in the timeout({})", timeout)
            DepositResult.Timeout
        }
    }
  }

  override def withdraw(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: BigInt,
  )(implicit appRequestContext: AppRequestContext): Future[WithdrawalResult] = {
    val shardId = replication.shardIdOf(BankAccountBehavior.typeKey, accountNo.value)
    if (isUnderMaintenance(shardId)) {
      logger.warn("The raft actor(shard id = {}) is under maintenance.", shardId)
      Future.successful(WithdrawalResult.UnderMaintenance)
    } else {
      AtLeastOnceComplete
        .askTo(entityRef(accountNo), BankAccountBehavior.Withdraw(transactionId, amount, _), retryInterval)
        .map {
          case BankAccountBehavior.WithdrawSucceeded(balance) => WithdrawalResult.Succeeded(balance)
          case BankAccountBehavior.ShortBalance()             => WithdrawalResult.ShortBalance
        }
        .recover {
          case cause: TimeoutException =>
            logger.warn(cause, "Could not get a response from the entity in the timeout({})", timeout)
            WithdrawalResult.Timeout
        }
    }
  }

  override def refund(
      accountNo: AccountNo,
      transactionId: TransactionId,
      withdrawalTransactionId: TransactionId,
      amount: BigInt,
  )(implicit appRequestContext: AppRequestContext): Future[BankAccountApplication.RefundResult] = {
    val shardId = replication.shardIdOf(BankAccountBehavior.typeKey, accountNo.value)
    if (isUnderMaintenance(shardId)) {
      logger.warn("The raft actor(shard id = {}) is under maintenance.", shardId)
      Future.successful(RefundResult.UnderMaintenance)
    } else {
      val refundCommand = BankAccountBehavior.Refund(transactionId, withdrawalTransactionId, amount, _)
      AtLeastOnceComplete
        .askTo(entityRef(accountNo), refundCommand, retryInterval)
        .map {
          case BankAccountBehavior.RefundSucceeded(balance) =>
            RefundResult.Succeeded(balance)
          case BankAccountBehavior.InvalidRefundCommand() =>
            RefundResult.InvalidArgument
        }
        .recover {
          case cause: TimeoutException =>
            logger.warn(cause, "Could not get a response from the entity in the timeout({})", timeout)
            RefundResult.Timeout
        }
    }
  }

}
