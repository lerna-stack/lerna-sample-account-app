package myapp.presentation

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import lerna.log.AppLogging
import myapp.presentation.application.{ ApplicationRoute, RemittanceRoute }
import myapp.presentation.management.VersionRoute

class RootRoute(
    appRoute: ApplicationRoute,
    remittanceRoute: RemittanceRoute,
    versionRoute: VersionRoute,
) extends AppLogging {
  // Put your route here

  def privateInternetRoute: Route = concat(
    appRoute.route,
    remittanceRoute.route,
  )

  /** システム内部からしか呼ばれない管理用のエンドポイントを定義する Route
    */
  def managementRoute: Route = concat(
    versionRoute.route,
  )
}
