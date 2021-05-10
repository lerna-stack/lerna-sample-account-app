package myapp.presentation

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
object PresentationDIDesign {
  @SuppressWarnings(Array("lerna.warts.CyclomaticComplexity"))
  val presentationDesign: Design = newDesign
    .bind[RootRoute].toSingleton
}
