package myapp.application.util.healthcheck

import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.JavaDurationOps

class JDBCHealthCheckSetting(root: Config) extends {
  private[this] val config    = root.getConfig("myapp.application.util.healthcheck.jdbc")
  val timeout: FiniteDuration = config.getDuration("timeout").toScala
}
