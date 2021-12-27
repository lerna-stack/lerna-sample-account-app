package myapp.entrypoint

import akka.actor.typed.ActorSystem
import akka.cluster.Cluster
import com.typesafe.config.ConfigFactory
import lerna.log.AppLogging
import lerna.util.encryption.EncryptionConfig

import scala.util.Failure

@SuppressWarnings(
  Array(
    "org.wartremover.contrib.warts.MissingOverride",
    "lerna.warts.Awaits",
  ),
)
object Main extends App with AppLogging {
  import lerna.log.SystemComponentLogContext.logContext

  private val config = ConfigFactory.load()

  val validationErrors = Seq(
    EncryptionConfig(config).validate(),
  ).collect {
    case Failure(exception) => exception
  }

  if (validationErrors.nonEmpty) {
    validationErrors.foreach { throwable =>
      logger.error(throwable, "起動時バリデーションエラー")
    }
    System.exit(1)
  }

  private val system: ActorSystem[Nothing] =
    ActorSystem[Nothing](AppGuardian(config), "MyAppSystem", config)
  logger.info("ActorSystem({})起動完了", system)

  val cluster = Cluster(system)
  logger.info("Akka Clusterへの参加待機中: {}", cluster.state)

  cluster.registerOnMemberUp {
    logger.info("Akka Clusterへの参加完了: {}", cluster.state)
  }
}
