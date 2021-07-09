package myapp.application.projection.transaction

import akka.Done
import akka.projection.eventsourced.EventEnvelope
import lerna.log.AppLogging
import myapp.application.account.{ BankAccountBehavior, BankAccountEventAdapter }
import myapp.application.account.BankAccountBehavior.{ BalanceShorted, Deposited, Withdrew }
import myapp.application.persistence.AggregateEventTag
import myapp.application.projection.AppEventHandler
import myapp.utility.AppRequestContext
import slick.dbio.DBIO

class BankTransactionEventHandler extends AppEventHandler[BankAccountBehavior.DomainEvent] with AppLogging {

  override protected def eventTag: AggregateEventTag[BankAccountBehavior.DomainEvent] =
    BankAccountEventAdapter.BankAccountTransactionEventTag

  override def process(envelope: EventEnvelope[BankAccountBehavior.DomainEvent]): DBIO[Done] = {
    implicit val requestContext: AppRequestContext = envelope.event.appRequestContext

    envelope.event match {
      case Deposited(transactionId, amount) =>
        logger.info("Deposited(transactionId: {}, amount: {})", transactionId, amount)
        DBIO.successful(Done)
      case Withdrew(transactionId, amount) =>
        logger.info("Withdrew(transactionId: {}, amount: {})", transactionId, amount)
        DBIO.successful(Done)
      case BalanceShorted(transactionId) =>
        logger.info("BalanceShorted(transactionId: {})", transactionId)
        DBIO.successful(Done)
    }
  }
}
