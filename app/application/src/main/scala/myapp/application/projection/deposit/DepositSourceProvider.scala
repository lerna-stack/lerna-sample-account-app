package myapp.application.projection.deposit

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.projection.scaladsl.SourceProvider
import akka.stream.scaladsl.Source
import lerna.log.AppLogging
import lerna.util.lang.Equals._
import myapp.readmodel.JDBCService
import myapp.readmodel.schema.Tables
import myapp.utility.tenant.AppTenant

import scala.concurrent.Future

private[deposit] class DepositSourceProvider(
    jdbcService: JDBCService,
    tables: Tables,
    config: DepositProjectionConfig,
)(implicit
    system: ActorSystem[Nothing],
    tenant: AppTenant,
) extends SourceProvider[DepositProjection.Offset, Deposit]
    with AppLogging {
  import DepositSourceProvider._
  import lerna.log.SystemComponentLogContext._
  import tables._
  import tables.profile.api._

  override def source(offset: () => Future[Option[DepositProjection.Offset]]): Future[Source[Deposit, NotUsed]] = {
    import system.executionContext
    offset().map(createSource)
  }

  override def extractOffset(envelope: Deposit): DepositProjection.Offset = envelope.depositId.value

  override def extractCreationTime(envelope: Deposit): Long = envelope.createdAt.toEpochMilli

  private[this] type OffsetOption = Option[DepositProjection.Offset]

  private[this] def createSource(initialOffset: OffsetOption): Source[Deposit, NotUsed] = {
    logger.info(s"Source created (Offset: ${initialOffset.toString})")
    Source
      .unfoldAsync[(OffsetOption, PollingControl), Seq[DepositStoreRow]]((initialOffset, Continue)) {
        case (offset, control) =>
          import system.executionContext
          def fetchNextBatch(): Future[Option[((OffsetOption, PollingControl), Seq[DepositStoreRow])]] = {
            for {
              deposits <- fetchDeposits(offset)
            } yield {
              // 結果が Offset (depositId) の昇順でソート済みのため、最後の行に最新の Offset があるとみなせる
              val nextOffset: OffsetOption    = deposits.lastOption.map(_.depositId).orElse(offset)
              val hasMoreEvents               = deposits.size === config.pollingBatchSize
              val nextControl: PollingControl = if (hasMoreEvents) Continue else ContinueDelayed
              Option(((nextOffset, nextControl), deposits))
            }
          }
          control match {
            case Continue        => fetchNextBatch()
            case ContinueDelayed => akka.pattern.after(config.pollingInterval)(fetchNextBatch())
          }
      }
      .mapConcat(identity)
      .map { row =>
        Deposit(DepositId(row.depositId), row.accountNo, BigInt(row.amount), row.createdAt.toInstant)
      }
  }

  private[this] def fetchDeposits(offset: OffsetOption): Future[Seq[DepositStoreRow]] = {
    val query = offset match {
      case Some(offset) =>
        DepositStore.filter(_.depositId > offset)
      case None =>
        DepositStore
    }
    // 結果を take で絞る前に sortBy するのが重要です
    // sortBy する前に take すると sortBy する前の結果の並びによっては取得するデータが欠損する危険性があります
    val action = query.sortBy(_.depositId.asc).take(num = config.pollingBatchSize).result
    jdbcService.db.run(action)
  }
}

private[deposit] object DepositSourceProvider {

  sealed trait PollingControl
  final case object Continue        extends PollingControl
  final case object ContinueDelayed extends PollingControl
}
