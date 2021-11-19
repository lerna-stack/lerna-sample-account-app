package myapp.adapter.account

import myapp.utility.AppRequestContext

import scala.concurrent.Future

trait BankAccountApplication {
  import BankAccountApplication._

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
    * 失敗の種類によっては、同じ 取引ID を指定してリトライする方が良い。
    * 例えば、 [[DepositResult.Timeout]] や 失敗した Future が返された場合は、
    * この入金が完了したかどうか(成功したか、失敗したか)を判断できない。
    * このような場合には、同じ 取引ID を指定してリトライする方が良い。
    * 取引ID により冪等性が保証されている。
    *
    * 同じ 取引ID を使用してリトライする場合、前回とまったく同じ入金金額を指定するべきである。
    * 異なる入金金額を指定して成功が返った場合、実際にいくら入金できたのかをこのメソッドの戻り値からは知ることはできない。
    * 今後の更新によって、このような場合に失敗を返すように変更される可能性がある。
    *
    * @param accountNo 口座番号
    * @param transactionId 取引ID
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
    * 失敗の種類によっては、同じ 取引ID を指定してリトライする方が良い。
    * 例えば、 [[WithdrawalResult.Timeout]] や 失敗した Future が返された場合は、
    * この出金が完了したかどうか(成功したか、失敗したか)を判断できない。
    * このような場合には、同じ 取引ID を指定してリトライする方が良い。
    * 取引ID により冪等性が保証されている。
    *
    * 同じ 取引ID を使用してリトライする場合、前回とまったく同じ出金金額を指定するべきである。
    * 異なる出金金額を指定して成功が返った場合、実際にいくら出金できたのかをこのメソッドの戻り値からは知ることはできない。
    * 今後の更新によって、このような場合に失敗を返すように変更される可能性がある。
    *
    * @param accountNo 口座番号
    * @param transactionId 取引ID
    * @param amount 出金金額
    * @return 出金結果を格納した [[Future]] を返す
    */
  def withdraw(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: BigInt,
  )(implicit appRequestContext: AppRequestContext): Future[WithdrawalResult]

  /** 指定口座に指定金額を返金する。
    *
    * 口座は、口座番号([[AccountNo]])とテナント([[myapp.utility.tenant.AppTenant]])で一意に決定される。
    *
    * 返金に成功した場合、返金後の残高を含む [[RefundResult.Succeeded]] を返す。
    * 想定される失敗は、[[RefundResult]] として返す。想定できない失敗は、失敗した [[Future]] として返す。
    *
    * 失敗の種類によっては、同じ 取引ID を指定してリトライする方が良い。
    * 例えば、 [[RefundResult.Timeout]] や 失敗した Future が返された場合は、
    * この返金が完了したかどうか(成功したか、失敗したか)を判断できない。
    * このような場合には、同じ 取引ID を指定してリトライする方が良い。
    * 取引ID により冪等性が保証されている。
    *
    * 同じ 取引ID を使用してリトライする場合、前回とまったく同じパラメータ(出金の取引ID, 返金金額)を指定しなければならない。
    * 異なるパラメータを指定した場合には [[RefundResult.InvalidArgument]] を返す。
    *
    * 出金の取引ID は、この返金を出金に関連づけるために使用できる。
    * このメソッドでは、次の内容は検証されない。
    *  - 出金の取引ID が存在するかどうか
    *  - 出金の取引ID が出金に対応するか
    * 出金の取引ID の妥当性は、このメソッドの利用者が検証しなければならない。
    *
    * @param accountNo 口座番号
    * @param transactionId 取引ID
    * @param withdrawalTransactionId 出金の取引ID
    */
  def refund(
      accountNo: AccountNo,
      transactionId: TransactionId,
      withdrawalTransactionId: TransactionId,
      amount: BigInt,
  )(implicit appRequestContext: AppRequestContext): Future[RefundResult]

  def getAccountStatement(accountNo: AccountNo)(implicit
      appRequestContext: AppRequestContext,
  ): Future[GetAccountStatementResult]
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
      * @note 同じ 取引ID を使用してリトライ '''できない'''
      */
    case object ExcessBalance extends DepositResult

    /** 入金失敗: タイムアウト
      *
      * 決められた時間内に入金処理が完了しなかった場合に発生する。
      *
      * タイムアウトが発生した場合、入金が処理されたかどうか(成功したか、失敗したか)を知ることができない。
      *
      * @note 同じ 取引ID を使用してリトライ '''できる'''
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
      * @note 同じ 取引ID を使用してリトライ'''できない'''
      */
    case object ShortBalance extends WithdrawalResult

    /** 出金失敗: タイムアウト
      *
      * 決められた時間内に出金処理が完了しなかった場合に発生する。
      *
      * タイムアウトが発生した場合、出金が処理されたかどうか(成功したか、失敗したか)を知ることができない。
      *
      * @note 同じ 取引ID を使用してリトライ'''できる'''
      */
    case object Timeout extends WithdrawalResult

  }

  /** 返金結果 */
  sealed trait RefundResult extends Product with Serializable
  object RefundResult {

    /** 返金成功
      * @param balance 返金後の残高
      */
    final case class Succeeded(balance: BigInt) extends RefundResult

    /** 返金失敗: 不正な引数
      *
      * 返金の引数が不正である。
      * 次に示すような理由で発生する。
      *  - 返金金額が0以下である
      *  - 前回と同じ取引IDに、前回とは異なるパラメータを指定した
      *
      * @note 同じ 取引ID を使用してリトライ'''できない'''
      */
    case object InvalidArgument extends RefundResult

    /** 返金失敗: タイムアウト
      *
      * 決められた時間内に返金処理が完了しなかった場合に発生する。
      *
      * タイムアウトが発生した場合、返金が処理されたかどうか(成功したか、失敗したか)を知ることができない。
      *
      * @note 同じ 返金ID を使用してリトライ'''できる'''
      */
    case object Timeout extends RefundResult

  }

  sealed trait GetAccountStatementResult extends Product with Serializable
  object GetAccountStatementResult {
    final case class Succeeded(statementDto: AccountStatementDto) extends GetAccountStatementResult
  }
}
