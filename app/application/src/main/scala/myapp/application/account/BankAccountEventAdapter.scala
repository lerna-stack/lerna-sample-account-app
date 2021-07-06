package myapp.application.account

import akka.actor.ExtendedActorSystem
import akka.event.Logging
import akka.persistence.journal.{ Tagged, WriteEventAdapter }

class BankAccountEventAdapter(system: ExtendedActorSystem) extends WriteEventAdapter {

  private[this] val log = Logging(system, getClass)

  override def manifest(event: Any): String = ""

  override def toJournal(event: Any): Any = event match {
    case domainEvent: BankAccountBehavior.DomainEvent =>
      val tags = Set("bank-account-transaction")
      Tagged(domainEvent, tags)
    case _ =>
      log.warning("unexpected event: {}", event)
      event
  }
}
