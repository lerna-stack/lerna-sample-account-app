package myapp.entrypoint

import akka.actor
import akka.actor.CoordinatedShutdown
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.TypedSchedulerOps
import akka.actor.typed.{ Behavior, PreRestart }
import com.typesafe.config.Config
import myapp.application.util.healthcheck.{ JDBCHealthCheck, JDBCHealthCheckFailureShutdown, JDBCHealthCheckService }
import myapp.entrypoint.Main.logger
import wvlet.airframe.Design

import scala.concurrent.Future

@SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
object AppGuardian {

  import lerna.log.SystemComponentLogContext.logContext

  def apply(config: Config): Behavior[Nothing] = Behaviors.setup[Nothing] { context =>
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

    import system.executionContext
    implicit val scheduler: actor.Scheduler = system.scheduler.toClassic
    val healthCheckRetrySetting             = session.build[AppGuardianSetting]
    val jDBCHealthCheck                     = new JDBCHealthCheck(system.classicSystem)
    val result = akka.pattern.retry(
      () =>
        jDBCHealthCheck().flatMap {
          case true  => Future.successful(true)
          case false => Future.failed(new IllegalStateException("Failed to DB connection"))
        },
      attempts = healthCheckRetrySetting.attempt,
      delay = healthCheckRetrySetting.delay,
    )
    for (_ <- result.failed) {
      CoordinatedShutdown(system).run(JDBCHealthCheckFailureShutdown)
    }

    Behaviors.receiveSignal[Nothing] {
      case (_, PreRestart) =>
        session.shutdown
        Behaviors.same
    }
  }

}
