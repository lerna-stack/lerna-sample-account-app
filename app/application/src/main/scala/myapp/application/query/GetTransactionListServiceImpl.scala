package myapp.application.query

import myapp.adapter.account.AccountNo
import myapp.adapter.query.{ GetTransactionListService, TransactionDto }
import myapp.readmodel.JDBCService
import myapp.readmodel.schema.Tables
import myapp.utility.tenant.AppTenant

import scala.concurrent.Future

final class GetTransactionListServiceImpl(
    jdbcService: JDBCService,
    tables: Tables,
) extends GetTransactionListService {
  import tables._
  import tables.profile.api._

  override def getTransactionList(
      accountNo: AccountNo,
      tenant: AppTenant,
      offset: Int,
      limit: Int,
  ): Future[Seq[TransactionDto]] = {
    val action: DBIO[Seq[TransactionDto]] =
      (TransactionStore joinLeft CommentStore)
        .on(_.transactionId === _.commentId)
        .filter(_._1.accountNo === accountNo.value)
        .sortBy(_._1.transactedAt.asc)
        .drop(offset)
        .take(limit)
        .map(row => {
          (
            row._1.transactionId,
            row._1.transactionType,
            row._1.amount,
            row._1.balance,
            row._1.transactedAt,
            row._2.map(_.comment).getOrElse(""),
          ).<>(TransactionDto.tupled, TransactionDto.unapply)
        }).result

    jdbcService.db(tenant).run(action)
  }
}
