package myapp.presentation.management

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.typesafe.config.Config

class VersionRoute(config: Config) {

  private[this] val conf = config.getConfig("myapp.presentation.versions")

  private[this] val version = conf.getString("version")

  private[this] val commitHash = conf.getString("commit-hash")

  def route: Route = {
    path("version") {
      complete(StatusCodes.OK -> version)
    } ~
    path("commit-hash") {
      complete(StatusCodes.OK -> commitHash)
    }
  }
}
