package myapp.presentation.application

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route

class ApplicationRoute {
  def route: Route = concat(
    path("index") {
      complete(StatusCodes.OK -> "OK")
    },
  )
}
