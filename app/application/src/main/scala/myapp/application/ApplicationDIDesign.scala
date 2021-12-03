package myapp.application

import myapp.adapter.account.{ BankAccountApplication, RemittanceApplication }
import myapp.adapter.query.{ CreateOrUpdateCommentService, ReadTransactionRepository }
import myapp.application.account.{ BankAccountApplicationImpl, RemittanceApplicationImpl }
import myapp.application.projection.transaction.{ TransactionRepository, TransactionRepositoryImpl }
import myapp.application.query.{ CreateOrUpdateCommentServiceImpl, ReadTransactionRepositoryImpl }
import wvlet.airframe.{ newDesign, Design }

/** Application プロジェクト内のコンポーネントの [[wvlet.airframe.Design]] を定義する
  */
// Airframe が生成するコードを Wartremover が誤検知してしまうため
@SuppressWarnings(
  Array(
    "org.wartremover.contrib.warts.MissingOverride",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Throw",
  ),
)
object ApplicationDIDesign {
  @SuppressWarnings(Array("lerna.warts.CyclomaticComplexity"))
  val applicationDesign: Design = newDesign
    .bind[TransactionRepository].to[TransactionRepositoryImpl]
    .bind[ReadTransactionRepository].to[ReadTransactionRepositoryImpl]
    .bind[CreateOrUpdateCommentService].to[CreateOrUpdateCommentServiceImpl]
    .bind[BankAccountApplication].to[BankAccountApplicationImpl]
    .bind[RemittanceApplication].to[RemittanceApplicationImpl]
}
