package myapp.adapter.account

import myapp.utility.AppRequestContext

import scala.concurrent.Future

trait BankAccountApplication {
  def fetchBalance(accountNo: AccountNo)(implicit appRequestContext: AppRequestContext): Future[BigInt]

  def deposit(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: Int,
  )(implicit appRequestContext: AppRequestContext): Future[BigInt]

  def withdraw(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: Int,
  )(implicit appRequestContext: AppRequestContext): Future[BigInt]
}
