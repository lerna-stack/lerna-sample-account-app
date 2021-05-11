package myapp.application

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import myapp.adapter.DepositImporterApplication

class DepositImporterApplicationImpl(system: ActorSystem, depositImportingManager: DepositImportingManager)
    extends DepositImporterApplication {

  override def importDeposit(): Unit = {
    val entity  = system.spawn(AccountEntityBehavior.apply(), "entity")
    val manager = system.spawn(depositImportingManager.createBehavior(entity), "manager")
    manager ! DepositImportingManager.Import()
  }
}
