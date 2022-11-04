package myapp.application.projection.transaction

import akka.Done
import akka.projection.eventsourced.EventEnvelope
import lerna.log.AppLogging
import myapp.application.account.BankAccountBehavior._
import myapp.application.account.{ BankAccountBehavior, BankAccountEventAdapter }
import myapp.application.persistence.AggregateEventTag
import myapp.application.projection.AppEventHandler
import myapp.utility.AppRequestContext
import slick.dbio.DBIO

class BankTransactionEventHandler(repository: TransactionRepository)
    extends AppEventHandler[BankAccountBehavior.DomainEvent]
    with AppLogging {

  override protected def eventTag: AggregateEventTag[BankAccountBehavior.DomainEvent] =
    BankAccountEventAdapter.BankAccountTransactionEventTag

  override def process(envelope: EventEnvelope[BankAccountBehavior.DomainEvent]): DBIO[Done] = {
    implicit val requestContext: AppRequestContext = envelope.event.appRequestContext

    envelope.event match {
      case Deposited(accountNo, _, transactionId, amount, balance, transactedAt) =>
        logger.info("Deposited(transactionId: {}, amount: {})", transactionId, amount)
        repository.save(
          Transaction(transactionId, TransactionEventType.Deposited, accountNo, amount, balance, transactedAt),
        )
      case BalanceExceeded(_, _, transactionId) =>
        logger.info("BalanceExceeded(transactionId: {})", transactionId)
        DBIO.successful(Done)
      case Withdrew(accountNo, _, transactionId, amount, balance, transactedAt) =>
        logger.info("Withdrew(transactionId: {}, amount: {})", transactionId, amount)
        repository.save(
          Transaction(transactionId, TransactionEventType.Withdrew, accountNo, amount, balance, transactedAt),
        )
      case BalanceShorted(_, _, transactionId) =>
        logger.info("BalanceShorted(transactionId: {})", transactionId)
        DBIO.successful(Done)
      case Refunded(accountNo, _, transactionId, withdrawalTransactionId, amount, balance, transactedAt) =>
        logger.info(
          "Refunded(transactionId: {}, withdrawalTransactionId: {}, amount: {})",
          transactionId,
          withdrawalTransactionId,
          amount,
        )
        repository.save(
          Transaction(transactionId, TransactionEventType.Refunded, accountNo, amount, balance, transactedAt),
        )
      case InvalidRefundRequested(_, _, transactionId, withdrawalTransactionId, amount) =>
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
