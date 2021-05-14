package myapp.application

import myapp.adapter.DepositImporterApplication
import myapp.application.deposit.DepositImporterApplicationImpl
import wvlet.airframe.{ newDesign, Design }

/** Presentation プロジェクト内のコンポーネントの [[wvlet.airframe.Design]] を定義する
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
    .bind[DepositImporterApplication].to[DepositImporterApplicationImpl]
}
