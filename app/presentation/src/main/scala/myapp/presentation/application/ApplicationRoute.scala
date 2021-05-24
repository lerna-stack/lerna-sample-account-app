package myapp.presentation.application

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import myapp.adapter.DepositImporterApplication

class ApplicationRoute(depositImporterApplication: DepositImporterApplication) {
  def route: Route = concat(
    path("import") {
      depositImporterApplication.importDeposit()
      complete(StatusCodes.Accepted -> "Accepted")
    },
  )
}
