package myapp.entrypoint

import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.JavaDurationOps

class AppGuardianSetting(root: Config) {
  private[this] val config  = root.getConfig("myapp.entrypoint.healthcheck.retry")
  val attempt: Int          = config.getInt("attempt")
  val delay: FiniteDuration = config.getDuration("delay").toScala
}
