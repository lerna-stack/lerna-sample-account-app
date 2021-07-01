package myapp.application

import myapp.adapter.account.BankAccountApplication
import myapp.application.account.BankAccountApplicationImpl
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
    .bind[BankAccountApplication].to[BankAccountApplicationImpl]
}