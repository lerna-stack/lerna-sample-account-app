package myapp.application.account

import akka.actor.typed.ActorSystem
import akka.util.Timeout
import lerna.akka.entityreplication.typed._
import lerna.akka.entityreplication.util.AtLeastOnceComplete
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionId }
import myapp.utility.AppRequestContext

import scala.concurrent.Future
import scala.concurrent.duration._

class BankAccountApplicationImpl(implicit system: ActorSystem[Nothing]) extends BankAccountApplication {
  import system.executionContext

  private[this] val replication = ClusterReplication(system)
  replication.init(ReplicatedEntity(BankAccountBehavior.TypeKey)(context => BankAccountBehavior(context)))

  private[this] implicit val timeout: Timeout = Timeout(10.seconds)

  private[this] val retryInterval: FiniteDuration = 300.milliseconds

  private[this] def entityRef(accountNo: AccountNo) =
    replication.entityRefFor(BankAccountBehavior.TypeKey, accountNo.value)

  override def fetchBalance(accountNo: AccountNo)(implicit appRequestContext: AppRequestContext): Future[BigInt] =
    AtLeastOnceComplete
      .askTo(entityRef(accountNo), BankAccountBehavior.GetBalance(_), retryInterval)
      .map(_.balance)

  override def deposit(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: Int,
  )(implicit appRequestContext: AppRequestContext): Future[BigInt] =
    AtLeastOnceComplete
      .askTo(entityRef(accountNo), BankAccountBehavior.Deposit(transactionId, amount, _), retryInterval)
      .map(_.balance)

  override def withdraw(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: Int,
  )(implicit appRequestContext: AppRequestContext): Future[BigInt] =
    AtLeastOnceComplete
      .askTo(entityRef(accountNo), BankAccountBehavior.Withdraw(transactionId, amount, _), retryInterval)
      .map {
        case BankAccountBehavior.WithdrawSucceeded(balance) => balance
        case BankAccountBehavior.ShortBalance()             => throw ??? // FIXME
      }
}
