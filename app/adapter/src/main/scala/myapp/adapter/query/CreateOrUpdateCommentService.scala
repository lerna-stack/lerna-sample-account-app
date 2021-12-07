package myapp.adapter.query

import myapp.adapter.Comment
import myapp.adapter.account.{ AccountNo, TransactionId }
import myapp.adapter.query.CreateOrUpdateCommentService.CreateOrUpdateCommentResult
import myapp.utility.tenant.AppTenant

import scala.concurrent.Future

trait CreateOrUpdateCommentService {
  def createOrUpdate(
      accountNo: AccountNo,
      transactionId: TransactionId,
      comment: Comment,
      appTenant: AppTenant,
  ): Future[CreateOrUpdateCommentResult]
}

object CreateOrUpdateCommentService {
  sealed trait CreateOrUpdateCommentResult
  object CreateOrUpdateCommentResult {
    case object Created             extends CreateOrUpdateCommentResult
    case object Updated             extends CreateOrUpdateCommentResult
    case object TransactionNotFound extends CreateOrUpdateCommentResult
  }
}
