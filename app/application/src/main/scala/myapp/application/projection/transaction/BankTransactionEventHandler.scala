package myapp.application.projection.transaction

import akka.Done
import akka.projection.eventsourced.EventEnvelope
import lerna.log.AppLogging
import myapp.application.account.{ BankAccountBehavior, BankAccountEventAdapter }
import myapp.application.account.BankAccountBehavior.{
  BalanceExceeded,
  BalanceShorted,
  Deposited,
  InvalidRefundRequested,
  Refunded,
  Withdrew,
}
import myapp.application.persistence.AggregateEventTag
import myapp.application.projection.AppEventHandler
import myapp.utility.AppRequestContext
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

class BankTransactionEventHandler(repository: TransactionRepository)(implicit ec: ExecutionContext)
  extends AppEventHandler[BankAccountBehavior.DomainEvent] with AppLogging {

  override protected def eventTag: AggregateEventTag[BankAccountBehavior.DomainEvent] =
    BankAccountEventAdapter.BankAccountTransactionEventTag

  override def process(envelope: EventEnvelope[BankAccountBehavior.DomainEvent]): DBIO[Done] = {
    implicit val requestContext: AppRequestContext = envelope.event.appRequestContext

    envelope.event match {
      case Deposited(transactionId, amount) =>
        logger.info("Deposited(transactionId: {}, amount: {})", transactionId, amount)
        repository.save(Transaction(transactionId.value, "Deposited", amount.toInt))
        DBIO.successful(Done)
      case BalanceExceeded(transactionId) =>
        logger.info("BalanceExceeded(transactionId: {})", transactionId)
        DBIO.successful(Done)
      case Withdrew(transactionId, amount) =>
        logger.info("Withdrew(transactionId: {}, amount: {})", transactionId, amount)
        DBIO.successful(Done)
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
        DBIO.successful(Done)
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
}
