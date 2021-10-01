package myapp.adapter.account

import myapp.adapter.account.BankAccountApplication.{ DepositResult, FetchBalanceResult, WithdrawalResult }
import myapp.utility.AppRequestContext

import scala.concurrent.Future

trait BankAccountApplication {

  /** 指定口座の残高を取得する。
    *
    * 口座は、口座番号([[AccountNo]])とテナント([[myapp.utility.tenant.AppTenant]])で一意に決定される。
    *
    * 想定される失敗は、[[FetchBalanceResult]] として返す。
    * 想定できない失敗は、失敗した [[Future]] として返す。
    * [[BankAccountApplication]] の実装内でタイムアウトが発生した場合は [[FetchBalanceResult.Timeout]] を返す。
    *
    * @param accountNo 口座番号
    * @return 残高照会結果を格納した [[Future]] を返す
    */
  def fetchBalance(accountNo: AccountNo)(implicit appRequestContext: AppRequestContext): Future[FetchBalanceResult]

  /** 指定口座に指定金額を入金する。
    *
    * 口座は、口座番号([[AccountNo]])とテナント([[myapp.utility.tenant.AppTenant]])で一意に決定される。
    *
    * 入金に成功した場合、入金後の残高を含む [[DepositResult.Succeeded]] を返す。
    * 想定される失敗は、[[DepositResult]] として返す。
    * 想定できない失敗は、失敗した [[Future]] として返す。
    *
    * 失敗の種類によっては、同じ 入金ID を指定してリトライする方が良い。
    * 例えば、 [[DepositResult.Timeout]] や 失敗した Future が返された場合は、
    * この入金が完了したかどうか(成功したか、失敗したか)を判断できない。
    * このような場合には、同じ 入金ID を指定してリトライする方が良い。
    * 入金ID により冪等性が保証されている。
    *
    * 同じ 入金ID を使用してリトライする場合、前回とまったく同じ入金金額を指定するべきである。
    * 異なる入金金額を指定して成功が返った場合、実際にいくら入金できたのかをこのメソッドの戻り値からは知ることはできない。
    * 今後の更新によって、このような場合に失敗を返すように変更される可能性がある。
    *
    * @param accountNo 口座番号
    * @param transactionId 入金ID
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
    *
    * 出金に成功した場合、出金後の残高を含む [[WithdrawalResult.Succeeded]] を返す。
    * 想定される失敗は、[[WithdrawalResult]] として返す。
    * 想定できない失敗は、失敗した [[Future]] として返す。
    *
    * 失敗の種類によっては、同じ 出金ID を指定してリトライする方が良い。
    * 例えば、 [[WithdrawalResult.Timeout]] や 失敗した Future が返された場合は、
    * この出金が完了したかどうか(成功したか、失敗したか)を判断できない。
    * このような場合には、同じ 出金ID を指定してリトライする方が良い。
    * 出金ID により冪等性が保証されている。
    *
    * 同じ 出金ID を使用してリトライする場合、前回とまったく同じ出金金額を指定するべきである。
    * 異なる出金金額を指定して成功が返った場合、実際にいくら出金できたのかをこのメソッドの戻り値からは知ることはできない。
    * 今後の更新によって、このような場合に失敗を返すように変更される可能性がある。
    *
    * @param accountNo 口座番号
    * @param transactionId 出金ID
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
      *
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

    /** 入金失敗: 残高不足
      *
      * @note 同じ 入金ID を使用してリトライ '''できない'''
      */
    case object ExcessBalance extends DepositResult

    /** 入金失敗: タイムアウト
      *
      * 決められた時間内に入金処理が完了しなかった場合に発生する。
      *
      * タイムアウトが発生した場合、入金が処理されたかどうか(成功したか、失敗したか)を知ることができない。
      *
      * @note 同じ 入金ID を使用してリトライ '''できる'''
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
      *
      * @note 同じ 出金ID を使用してリトライ'''できない'''
      */
    case object ShortBalance extends WithdrawalResult

    /** 出金失敗: タイムアウト
      *
      * 決められた時間内に出金処理が完了しなかった場合に発生する。
      *
      * タイムアウトが発生した場合、出金が処理されたかどうか(成功したか、失敗したか)を知ることができない。
      *
      * @note 同じ 出金ID を使用してリトライ'''できる'''
      */
    case object Timeout extends WithdrawalResult

  }

}
