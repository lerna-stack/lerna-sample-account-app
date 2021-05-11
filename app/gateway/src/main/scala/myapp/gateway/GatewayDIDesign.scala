package myapp.gateway

import myapp.adapter.DepositPoolGateway
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
object GatewayDIDesign {

  val gatewayDesign: Design = newDesign
    .bind[DepositPoolGateway].to[DepositPoolGatewayImpl]

}
