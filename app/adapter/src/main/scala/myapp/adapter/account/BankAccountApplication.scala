package myapp.adapter.account

import scala.concurrent.Future

trait BankAccountApplication {
  def fetchBalance(accountNo: AccountNo): Future[BigDecimal]

  def deposit(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: Int,
  ): Future[BigDecimal]

  def withdraw(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: Int,
  ): Future[BigDecimal]
}
