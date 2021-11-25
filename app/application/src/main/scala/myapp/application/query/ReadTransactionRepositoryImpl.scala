package myapp.application.query

import myapp.adapter.account.{ AccountNo, TransactionDto }
import myapp.adapter.query.ReadTransactionRepository
import myapp.readmodel.JDBCService
import myapp.readmodel.schema.Tables
import myapp.utility.tenant.AppTenant

import scala.concurrent.Future

final class ReadTransactionRepositoryImpl(
    jdbcService: JDBCService,
    tables: Tables,
) extends ReadTransactionRepository {
  import tables._
  import tables.profile.api._

  override def getTransactionList(
      accountNo: AccountNo,
      tenant: AppTenant,
      offset: Int,
      limit: Int,
  ): Future[Seq[TransactionDto]] = {
    val action: DBIO[Seq[TransactionDto]] =
      TransactionStore
        .filter(_.accountNo === accountNo.value)
        .sortBy(_.transactedAt.asc)
        .drop(offset)
        .take(limit)
        .map(row => {
          (
            row.transactionId,
            row.transactionType,
            row.amount,
            row.balance,
            row.transactedAt,
          ).<>(TransactionDto.tupled, TransactionDto.unapply)
        }).result

    jdbcService.db(tenant).run(action)
  }
}
