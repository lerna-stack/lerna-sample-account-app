package myapp.application.query

import akka.actor.typed.ActorSystem
import myapp.adapter.account.{ AccountNo, TransactionId }
import myapp.adapter.query.DeleteCommentService
import myapp.adapter.query.DeleteCommentService.DeleteCommentResult
import myapp.application.projection.transaction.TransactionRepository
import myapp.readmodel.JDBCService
import myapp.utility.tenant.AppTenant

import scala.concurrent.Future

class DeleteCommentServiceImpl(
    transactionRepository: TransactionRepository,
    jdbcService: JDBCService,
    system: ActorSystem[Nothing],
) extends DeleteCommentService {

  import system.executionContext

  override def delete(
      accountNo: AccountNo,
      transactionId: TransactionId,
      appTenant: AppTenant,
  ): Future[DeleteCommentService.DeleteCommentResult] = {
    jdbcService.db(appTenant).run(transactionRepository.findById(transactionId)) flatMap {
      case None => Future.successful(DeleteCommentResult.TransactionNotFound)
      case Some(transaction) =>
        jdbcService
          .db(appTenant).run(transactionRepository.save(transaction.copy(comment = None))).map(_ =>
            DeleteCommentResult.Deleted,
          )
    }
  }
}
