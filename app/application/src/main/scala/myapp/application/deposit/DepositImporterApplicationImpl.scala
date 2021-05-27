package myapp.application.deposit

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.ShardingEnvelope
import myapp.adapter.DepositImporterApplication
import myapp.application.account.AccountEntityBehavior

class DepositImporterApplicationImpl(system: ActorSystem, depositImportingManager: DepositImportingManager)
    extends DepositImporterApplication {

  private[this] val region  = AccountEntityBehavior.shardRegion(system.toTyped)
  private[this] val manager = depositImportingManager.shardRegion(system.toTyped, region)

  override def importDeposit(): Unit = {
    manager ! ShardingEnvelope("test", DepositImportingManager.Import())
  }
}
