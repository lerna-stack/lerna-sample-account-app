package myapp.adapter.query

import myapp.adapter.account.{ AccountNo, TransactionId }
import myapp.adapter.query.DeleteCommentService.DeleteCommentResult
import myapp.utility.tenant.AppTenant

import scala.concurrent.Future

trait DeleteCommentService {
  def delete(
      accountNo: AccountNo,
      transactionId: TransactionId,
      appTenant: AppTenant,
  ): Future[DeleteCommentResult]
}

object DeleteCommentService {
  sealed trait DeleteCommentResult
  object DeleteCommentResult {
    case object Deleted             extends DeleteCommentResult
    case object TransactionNotFound extends DeleteCommentResult
  }
}
