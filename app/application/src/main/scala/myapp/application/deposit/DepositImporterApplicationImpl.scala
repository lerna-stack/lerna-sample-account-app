package myapp.application.deposit

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import myapp.adapter.DepositImporterApplication
import myapp.application.account.AccountEntityBehavior

class DepositImporterApplicationImpl(system: ActorSystem, depositImportingManager: DepositImportingManager)
    extends DepositImporterApplication {

  private[this] val entity  = system.spawn(AccountEntityBehavior("bank", "0001"), "entity")
  private[this] val manager = system.spawn(depositImportingManager.createBehavior(entity), "manager")

  override def importDeposit(): Unit = {
    manager ! DepositImportingManager.Import()
  }
}
