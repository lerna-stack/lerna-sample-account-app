package myapp.entrypoint

import akka.Done
import akka.actor.{ ActorSystem, CoordinatedShutdown }
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import com.typesafe.config.Config
import lerna.log.AppLogging
import lerna.util.time.JavaDurationConverters._
import myapp.gateway.DataSourceSystemMockGateway
import myapp.presentation.RootRoute

@SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
class MyApp(implicit
    val actorSystem: ActorSystem,
    rootRoute: RootRoute,
    config: Config,
    dataSourceSystemMockGateway: DataSourceSystemMockGateway,
) extends AppLogging {
  import actorSystem.dispatcher

  def start(): Unit = {
    dataSourceSystemMockGateway.start()

    val privateInternetInterface = config.getString("myapp.private-internet.http.interface")
    val privateInternetPort      = config.getInt("myapp.private-internet.http.port")

    startServer("private-internet", rootRoute.privateInternetRoute, privateInternetInterface, privateInternetPort)

    val managementInterface = config.getString("myapp.management.http.interface")
    val managementPort      = config.getInt("myapp.management.http.port")
    startServer("management", rootRoute.managementRoute, managementInterface, managementPort)
  }

  private[this] def startServer(typeName: String, route: Route, interface: String, port: Int)(implicit
      fm: Materializer,
  ): Unit = {
    Http()
      .bindAndHandle(route, interface, port)
      .foreach { serverBinding =>
        addToShutdownHook(typeName, serverBinding)
      }
  }

  private[this] def addToShutdownHook(typeName: String, serverBinding: ServerBinding): Unit = {
    import lerna.log.SystemComponentLogContext.logContext

    val coordinatedShutdown = CoordinatedShutdown(actorSystem)

    coordinatedShutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, s"http-unbind-$typeName") { () =>
      logger.info(s"[$typeName] 終了処理のため、$serverBinding をunbindします")

      serverBinding.unbind().map(_ => Done)
    }

    coordinatedShutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, s"http-graceful-terminate-$typeName") {
      () =>
        val hardDeadline =
          config.getDuration("myapp.entrypoint.graceful-termination.hard-deadline").asScala

        logger.info(s"[$typeName] 終了処理のため、$serverBinding の graceful terminate を開始します（最大で $hardDeadline 待ちます）")

        serverBinding.terminate(hardDeadline) map { _ =>
          logger.info(s"[$typeName] 終了処理のための $serverBinding の graceful terminate が終了しました")

          Done
        }
    }
  }

}
