package myapp.adapter.account

import myapp.adapter.account.BankAccountApplication.{ DepositResult, FetchBalanceResult, WithdrawalResult }
import myapp.utility.AppRequestContext

import scala.concurrent.Future

trait BankAccountApplication {

  /** 指定口座の残高を取得する。
    *
    * 口座は、口座番号([[AccountNo]])とテナント([[myapp.utility.tenant.AppTenant]])で一意に決定される。
    *
    * @param accountNo 口座番号
    * @return 残高照会結果を格納した [[Future]] を返す
    */
  def fetchBalance(accountNo: AccountNo)(implicit appRequestContext: AppRequestContext): Future[FetchBalanceResult]

  /** 指定口座に指定金額を入金する。
    *
    * 口座は、口座番号([[AccountNo]])とテナント([[myapp.utility.tenant.AppTenant]])で一意に決定される。
    * `transactionId` により冪等性が保証される。
    *
    * @param accountNo 口座番号
    * @param transactionId トランザクションID
    * @param amount 入金金額
    * @return 入金結果を格納した [[Future]] を返す
    */
  def deposit(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: BigInt,
  )(implicit appRequestContext: AppRequestContext): Future[DepositResult]

  /** 指定口座から指定金額を出金する。
    *
    * 口座は、口座番号([[AccountNo]])とテナント([[myapp.utility.tenant.AppTenant]])で一意に決定される。
    * `transactionId` により冪等性が保証される。
    *
    * @param accountNo 口座番号
    * @param transactionId トランザクションID
    * @param amount 出金金額
    * @return 出金結果を格納した [[Future]] を返す
    */
  def withdraw(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: BigInt,
  )(implicit appRequestContext: AppRequestContext): Future[WithdrawalResult]

}

object BankAccountApplication {

  /** 残高照会結果 */
  sealed trait FetchBalanceResult extends Product with Serializable
  object FetchBalanceResult {

    /** 残高照会成功
      * @param balance 残高
      */
    final case class Succeeded(balance: BigInt) extends FetchBalanceResult

    /** 残高照会失敗: タイムアウト
      * 決められた時間内に残高照会できなかった場合に発生する。
      */
    case object Timeout extends FetchBalanceResult

  }

  /** 入金結果 */
  sealed trait DepositResult extends Product with Serializable
  object DepositResult {

    /** 入金成功
      * @param balance 入金後の残高
      */
    final case class Succeeded(balance: BigInt) extends DepositResult

    /** 入金失敗: 残高不足 */
    case object ExcessBalance extends DepositResult

    /** 入金失敗: タイムアウト
      *
      * 決められた時間内に入金処理が完了しなかった場合に発生する。
      *
      * タイムアウトが発生した場合、入金が処理されたかどうか(成功したか、失敗したか)を知ることができない。
      * 同じ [[TransactionId]] を用いてリトライする方が良い。
      */
    case object Timeout extends DepositResult

  }

  /** 出金結果 */
  sealed trait WithdrawalResult extends Product with Serializable
  object WithdrawalResult {

    /** 出金成功
      * @param balance 出金後の残高
      */
    final case class Succeeded(balance: BigInt) extends WithdrawalResult

    /** 出金失敗: 残高不足
      */
    case object ShortBalance extends WithdrawalResult

    /** 出金失敗: タイムアウト
      *
      * 決められた時間内に出金処理が完了しなかった場合に発生する。
      *
      * タイムアウトが発生した場合、出金が処理されたかどうか(成功したか、失敗したか)を知ることができない。
      * 同じ [[TransactionId]] を用いてリトライする方が良い。
      */
    case object Timeout extends WithdrawalResult

  }

}
