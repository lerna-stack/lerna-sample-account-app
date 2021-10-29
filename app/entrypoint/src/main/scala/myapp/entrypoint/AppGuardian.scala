package myapp.entrypoint

import akka.actor.CoordinatedShutdown
import akka.actor.typed.{ Behavior, PreRestart }
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.Config
import myapp.application.util.healthcheck.JDBCHealthCheckService
import myapp.entrypoint.Main.{ config, logger, system }
import wvlet.airframe.Design

import scala.concurrent.Future

object AppGuardian {

  import lerna.log.SystemComponentLogContext.logContext

  def apply(config: Config): Behavior[Nothing] = Behaviors.setup { context =>
    val system     = context.system
    val serverMode = config.getString("myapp.server-mode")
    val design: Design = serverMode match {
      case "PRODUCTION" => DIDesign.design(system).withProductionMode
      case "DEV"        => DIDesign.design(system)
      case _            => throw new IllegalStateException(s"Illegal server-mode: $serverMode")
    }

    val session = design.newSessionBuilder.noShutdownHook.create
    session.start

    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceStop, taskName = "shutdown-airframe-session") {
      () =>
        logger.info(s"終了処理のため、airframe session を shutdown します")
        session.shutdown

        Future.successful(akka.Done)
    }

    session.build[MyApp].start()
    context.spawn(session.build[JDBCHealthCheckService].createBehavior(), "JDBCHealthChecker")

    Behaviors.receiveSignal {
      case (_, PreRestart) =>
        session.shutdown
        Behaviors.same
    }
  }

}
