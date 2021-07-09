package myapp.application.account

import akka.actor.ExtendedActorSystem
import akka.event.Logging
import akka.persistence.journal.{ Tagged, WriteEventAdapter }
import myapp.application.persistence.AggregateEventTag

object BankAccountEventAdapter {
  val BankAccountTransactionEventTag: AggregateEventTag[BankAccountBehavior.DomainEvent] =
    AggregateEventTag("bank-account-transaction")
}

class BankAccountEventAdapter(system: ExtendedActorSystem) extends WriteEventAdapter {
  import BankAccountEventAdapter._

  private[this] val log = Logging(system, getClass)

  override def manifest(event: Any): String = ""

  override def toJournal(event: Any): Any = event match {
    case domainEvent: BankAccountBehavior.DomainEvent =>
      val tags = Set(BankAccountTransactionEventTag.tag)
      Tagged(domainEvent, tags)
    case _ =>
      log.warning("unexpected event: {}", event)
      event
  }
}
