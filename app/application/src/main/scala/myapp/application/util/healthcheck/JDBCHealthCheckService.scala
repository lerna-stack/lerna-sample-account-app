package myapp.application.util.healthcheck

import akka.actor.typed.receptionist.{ Receptionist, ServiceKey }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }
import myapp.readmodel.JDBCService

import scala.util.{ Failure, Success, Try }

object JDBCHealthCheckService {

  val JDBCHealthCheckServiceKey: ServiceKey[Command] = ServiceKey("JDBCHealthChecker")

  sealed trait Command

  final case class GetCurrentStatus(replyTo: ActorRef[GetCurrentStatusReply]) extends Command

  sealed trait GetCurrentStatusReply
  final case object Healthy   extends GetCurrentStatusReply
  final case object Unhealthy extends GetCurrentStatusReply

  private final case object Tick extends Command

  private final case class HealthCheckSucceeded()              extends Command
  private final case class HealthCheckFailed(cause: Throwable) extends Command

  private final case object TickTimerKey
}

class JDBCHealthCheckService(
    jdbcHealthCheckApplication: JDBCHealthCheckApplication,
    settings: JDBCHealthCheckServiceSettings,
) {
  import JDBCHealthCheckService._

  def createBehavior(): Behavior[Command] = Behaviors.setup { context =>
    context.system.receptionist ! Receptionist.Register(JDBCHealthCheckServiceKey, context.self)
    unhealthy()
  }

  private[this] def healthy(failureCount: Int = 0): Behavior[Command] = Behaviors.withTimers { timers =>
    timers.startSingleTimer(TickTimerKey, Tick, settings.interval)

    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case GetCurrentStatus(replyTo) =>
          replyTo ! Healthy
          Behaviors.same
        case Tick =>
          check(context)
        case HealthCheckSucceeded() =>
          healthy() // reset
        case HealthCheckFailed(cause) =>
          val newFailureCount = failureCount - 1
          if (newFailureCount >= settings.unhealthyThreshold) {
            unhealthy()
          } else {
            healthy(newFailureCount)
          }
      }
    }
  }

  private[this] def unhealthy(successCount: Int = 0): Behavior[Command] = Behaviors.withTimers { timers =>
    timers.startSingleTimer(TickTimerKey, Tick, settings.interval)

    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case GetCurrentStatus(replyTo) =>
          replyTo ! Unhealthy
          Behaviors.same
        case Tick =>
          check(context)
        case HealthCheckSucceeded() =>
          val newSuccessCount = successCount - 1
          if (newSuccessCount >= settings.healthyThreshold) {
            healthy()
          } else {
            unhealthy(newSuccessCount)
          }
        case HealthCheckFailed(cause) =>
          unhealthy() // reset
      }
    }
  }

  private[this] def check(context: ActorContext[Command]): Behavior[Command] = {
    context.pipeToSelf(jdbcHealthCheckApplication.check()) {
      case Success(value) => HealthCheckSucceeded()
      case Failure(cause) => HealthCheckFailed(cause)
    }
    Behaviors.same
  }

}
