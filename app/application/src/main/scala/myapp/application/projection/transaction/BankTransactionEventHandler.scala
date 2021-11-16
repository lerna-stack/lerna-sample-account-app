package myapp.application.projection.transaction

import akka.Done
import akka.actor.typed.ActorSystem
import akka.projection.eventsourced.EventEnvelope
import lerna.log.AppLogging
import myapp.application.account.BankAccountBehavior._
import myapp.application.account.{ BankAccountBehavior, BankAccountEventAdapter }
import myapp.application.persistence.AggregateEventTag
import myapp.application.projection.AppEventHandler
import myapp.utility.AppRequestContext
import slick.dbio.DBIO

class BankTransactionEventHandler(system: ActorSystem[Nothing], repository: TransactionRepository)
    extends AppEventHandler[BankAccountBehavior.DomainEvent]
    with AppLogging {

  implicit val ec = system.executionContext

  override def process(envelope: EventEnvelope[BankAccountBehavior.DomainEvent]): DBIO[Done] = {
    implicit val requestContext: AppRequestContext = envelope.event.appRequestContext

    envelope.event match {
      case Deposited(transactionId, amount) =>
        logger.info("Deposited(transactionId: {}, amount: {})", transactionId, amount)
        repository.save(Transaction(transactionId, TransactionEventType.Deposited, amount))
      case BalanceExceeded(transactionId) =>
        logger.info("BalanceExceeded(transactionId: {})", transactionId)
        DBIO.successful(Done)
      case Withdrew(transactionId, amount) =>
        logger.info("Withdrew(transactionId: {}, amount: {})", transactionId, amount)
        repository.save(Transaction(transactionId, TransactionEventType.Withdrew, amount))
      case BalanceShorted(transactionId) =>
        logger.info("BalanceShorted(transactionId: {})", transactionId)
        DBIO.successful(Done)
      case Refunded(transactionId, withdrawalTransactionId, amount) =>
        logger.info(
          "Refunded(transactionId: {}, withdrawalTransactionId: {}, amount: {})",
          transactionId,
          withdrawalTransactionId,
          amount,
        )
        repository.save(Transaction(transactionId, TransactionEventType.Refunded, amount))
      case InvalidRefundRequested(transactionId, withdrawalTransactionId, amount) =>
        logger.info(
          "InvalidRefundRequested(transactionId: {}, withdrawalTransactionId: {}, amount: {}",
          transactionId,
          withdrawalTransactionId,
          amount,
        )
        DBIO.successful(Done)
    }
  }

  override protected def eventTag: AggregateEventTag[BankAccountBehavior.DomainEvent] =
    BankAccountEventAdapter.BankAccountTransactionEventTag
}
