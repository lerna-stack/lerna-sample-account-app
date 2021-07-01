package myapp.application.account

import akka.actor.typed.ActorSystem
import akka.util.Timeout
import lerna.akka.entityreplication.typed._
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionId }
import myapp.utility.AppRequestContext

import scala.concurrent.Future
import scala.concurrent.duration._

class BankAccountApplicationImpl(system: ActorSystem[Nothing]) extends BankAccountApplication {
  import system.executionContext

  private[this] val replication = ClusterReplication(system)
  replication.init(ReplicatedEntity(BankAccountBehavior.TypeKey)(context => BankAccountBehavior(context)))

  private[this] implicit val timeout: Timeout = Timeout(10.seconds)

  private[this] def entityRef(accountNo: AccountNo) =
    replication.entityRefFor(BankAccountBehavior.TypeKey, accountNo.value)

  override def fetchBalance(accountNo: AccountNo)(implicit appRequestContext: AppRequestContext): Future[BigDecimal] =
    entityRef(accountNo)
      .ask[BankAccountBehavior.AccountBalance](BankAccountBehavior.GetBalance(_))
      .map(_.balance)

  override def deposit(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: Int,
  )(implicit appRequestContext: AppRequestContext): Future[BigDecimal] =
    entityRef(accountNo)
      .ask[BankAccountBehavior.DepositSucceeded](BankAccountBehavior.Deposit(transactionId, amount, _))
      .map(_.balance)

  override def withdraw(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: Int,
  )(implicit appRequestContext: AppRequestContext): Future[BigDecimal] =
    entityRef(accountNo)
      .ask[BankAccountBehavior.WithdrawReply](BankAccountBehavior.Withdraw(transactionId, amount, _))
      .map {
        case BankAccountBehavior.WithdrawSucceeded(balance) => balance
        case BankAccountBehavior.ShortBalance()             => throw ??? // FIXME
      }
}
