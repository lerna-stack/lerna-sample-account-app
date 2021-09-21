package myapp.adapter.account

import myapp.utility.AppRequestContext

import scala.concurrent.Future

trait BankAccountApplication {

  /** 指定口座の残高を取得する。
    *
    * 口座は、口座番号([[AccountNo]])とテナント([[myapp.utility.tenant.AppTenant]])で一意に決定される。
    *
    * @param accountNo 口座番号
    * @return 残高を格納した [[Future]] を返す
    */
  def fetchBalance(accountNo: AccountNo)(implicit appRequestContext: AppRequestContext): Future[BigInt]

  /** 指定口座に指定金額を入金する。
    *
    * 口座は、口座番号([[AccountNo]])とテナント([[myapp.utility.tenant.AppTenant]])で一意に決定される。
    * `transactionId` により冪等性が保証される。
    *
    * @param accountNo 口座番号
    * @param transactionId トランザクションID
    * @param amount 入金金額
    * @return 入金後の残高を格納した [[Future]] を返す
    */
  def deposit(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: BigInt,
  )(implicit appRequestContext: AppRequestContext): Future[BigInt]

  /** 指定口座から指定金額を出金する。
    *
    * 口座は、口座番号([[AccountNo]])とテナント([[myapp.utility.tenant.AppTenant]])で一意に決定される。
    * `transactionId` により冪等性が保証される。
    *
    * @param accountNo 口座番号
    * @param transactionId トランザクションID
    * @param amount 出金金額
    * @return 出金後の残高を格納した [[Future]] を返す
    */
  def withdraw(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: BigInt,
  )(implicit appRequestContext: AppRequestContext): Future[BigInt]

}
