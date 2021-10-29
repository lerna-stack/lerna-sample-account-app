package myapp.application.util.healthcheck

import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration

class JDBCHealthCheckServiceSettings(root: Config) {

  val interval: FiniteDuration = ???

  val healthyThreshold: Int = ???

  val unhealthyThreshold: Int = ???

}
