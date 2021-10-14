package myapp.adapter.account

import myapp.utility.AppRequestContext

import scala.concurrent.Future

trait RemittanceApplication {
  import RemittanceApplication._

  /** 送金元口座から送金先口座に指定金額を送金する。
    *
    * 送金に成功した場合、 [[RemitResult.Succeeded]] を返す。
    * 想定される失敗は、[[RemitResult]] として返す。
    * 想定できない失敗は、失敗した [[Future]] として返す。
    *
    * 送金元口座と送金先口座は同一テナントに存在する必要がある。
    * 口座は、口座番号([[AccountNo]])とテナント([[myapp.utility.tenant.AppTenant]])で一意に決定される。
    *
    * 失敗の種類によっては、同じ 送金取引ID を指定してリトライする方が良い。
    * 例えば、 [[RemitResult.Timeout]] や 失敗した Future が返された場合は、
    * この送金が完了したかどうか(成功したか、失敗したか)を判断できない。
    * このような場合には、同じ 送金取引ID を指定してリトライする方が良い。
    * 送金取引ID により、このメソッドの冪等性が保証される。
    *
    * 同じ 送金取引ID を使用してリトライする場合、前回とまったく同じ引数（送金元口座番号、送金先口座番号、送金金額）を指定しなければならない。
    * 異なる引数 を指定した場合には [[RemitResult.InvalidArgument]] を返す。
    *
    * @param sourceAccountNo  送金元口座番号
    * @param destinationAccountNo 送金先口座番号
    * @param transactionId 送金取引ID
    * @param amount 送金金額
    * @return 送金結果を格納した [[Future]] を返す
    */
  def remit(
      sourceAccountNo: AccountNo,
      destinationAccountNo: AccountNo,
      transactionId: RemittanceTransactionId,
      amount: BigInt,
  )(implicit appRequestContext: AppRequestContext): Future[RemitResult]

}

object RemittanceApplication {

  /** 送金取引ID
    *
    * テナント内で送金取引を一意に識別するために使用する。
    * 入金、出金、返金で使用する [[TransactionId]] とは異なる。
    */
  final case class RemittanceTransactionId(value: String) extends AnyVal

  /** 送金結果 */
  sealed trait RemitResult extends Product with Serializable
  object RemitResult {

    /** 送金成功 */
    case object Succeeded extends RemitResult

    /** 送金失敗:不正な引数
      *
      * 次に示すいくつかの理由で発生する。
      *  - 送金元と送金先が同じ口座である
      *  - 送金金額が0以下である
      *  - 前回と同じ送金IDに、前回とは異なる引数を指定した
      *
      * @note 同じ 送金取引ID を使用してリトライ'''できない'''
      */
    case object InvalidArgument extends RemitResult

    /** 送金失敗:残高不足
      *
      * 送金によって送金元口座が残高不足になる場合に発生する。
      *
      * @note 同じ 送金取引ID を使用してリトライ'''できない'''
      */
    case object ShortBalance extends RemitResult

    /** 送金失敗:残高超過
      *
      * 送金によって送金先口座が残高超過となる場合に発生する。
      *
      * @note 同じ 送金取引ID を使用してリトライ'''できない'''
      */
    case object ExcessBalance extends RemitResult

    /** 送金失敗: タイムアウト
      *
      * 決められた時間内に送金処理が完了しなかった場合に発生する。
      *
      * タイムアウトが発生した場合、送金が処理されたかどうか(成功したか、失敗したか)を知ることができない。
      *
      * @note 同じ 送金取引ID を使用してリトライ'''できる'''
      */
    case object Timeout extends RemitResult
  }

}
