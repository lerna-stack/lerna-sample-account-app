package myapp.application.util.healthcheck

import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.JavaDurationOps

class JDBCHealthCheckServiceSettings(root: Config) {

  private[this] val config     = root.getConfig("myapp.application.util.healthcheck.jdbc")
  val interval: FiniteDuration = config.getDuration("interval").toScala

  val healthyThreshold: Int = config.getInt("healthy-threshold")

  val unhealthyThreshold: Int = config.getInt("unhealthy-threshold")

}
