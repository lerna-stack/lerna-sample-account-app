package myapp.application.projection.transaction

import akka.Done
import akka.projection.eventsourced.EventEnvelope
import lerna.log.AppLogging
import lerna.util.trace.TraceId
import myapp.application.account.BankAccountBehavior
import myapp.application.account.BankAccountBehavior.{ BalanceShorted, Deposited, Withdrew }
import myapp.application.projection.AppEventHandler
import myapp.utility.tenant.AppTenant
import slick.dbio.DBIO

class BankTransactionEventHandler(implicit tenant: AppTenant)
    extends AppEventHandler[BankAccountBehavior.DomainEvent]
    with AppLogging {

  override protected def subscribeEventTag = "bank-account-transaction"

  private[this] implicit val traceId: TraceId = TraceId.unknown

  override def process(envelope: EventEnvelope[BankAccountBehavior.DomainEvent]): DBIO[Done] = envelope.event match {
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
