package myapp.application.projection.deposit

import akka.util.Timeout
import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters._

class DepositProjectionConfig(root: Config) {

  private[this] val config = root.getConfig("myapp.application.projection.deposit")

  val pollingInterval: FiniteDuration = config.getDuration("polling-interval").toScala

  val pollingBatchSize: Int = config.getInt("polling-batch-size")

  val maxCommandThroughputPerSec: Int = config.getInt("max-command-throughput-per-sec")

  val commandTimeout: Timeout = Timeout(config.getDuration("command-timeout").toScala)

  val commandParallelism: Int = config.getInt("command-parallelism")
}
