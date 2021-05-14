package myapp.application.deposit

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import myapp.adapter.DepositImporterApplication
import myapp.application.account.AccountEntityBehavior

class DepositImporterApplicationImpl(system: ActorSystem, depositImportingManager: DepositImportingManager)
    extends DepositImporterApplication {

  private[this] val region  = AccountEntityBehavior.shardRegion(system.toTyped)
  private[this] val manager = system.spawn(depositImportingManager.createBehavior(region), "manager")

  override def importDeposit(): Unit = {
    manager ! DepositImportingManager.Import()
  }
}
