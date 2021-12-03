package myapp.application.query

import akka.actor.typed.ActorSystem
import myapp.adapter.Comment
import myapp.adapter.account.{ AccountNo, TransactionId }
import myapp.adapter.query.CreateOrUpdateCommentService
import myapp.adapter.query.CreateOrUpdateCommentService.CreateOrUpdateCommentResult
import myapp.application.projection.transaction.TransactionRepository
import myapp.readmodel.JDBCService
import myapp.utility.tenant.AppTenant

import scala.concurrent.Future

class CreateOrUpdateCommentServiceImpl(
    transactionRepository: TransactionRepository,
    jdbcService: JDBCService,
    system: ActorSystem[Nothing],
) extends CreateOrUpdateCommentService {
  import system.executionContext

  override def createOrUpdate(
      accountNo: AccountNo,
      transactionId: TransactionId,
      comment: Comment,
      appTenant: AppTenant,
  ): Future[CreateOrUpdateCommentResult] = {
    val futureOptionTransaction = jdbcService.db(appTenant).run(transactionRepository.findById(transactionId))
    futureOptionTransaction.flatMap {
      case None => Future.successful(CreateOrUpdateCommentResult.TransactionNotFound)
      case Some(transaction) =>
        jdbcService
          .db(appTenant).run(transactionRepository.save(transaction.copy(comment = Option(comment))))
          .map { _ =>
            transaction.comment match {
              case None    => CreateOrUpdateCommentResult.Created
              case Some(_) => CreateOrUpdateCommentResult.Updated
            }
          }
    }
  }
}
