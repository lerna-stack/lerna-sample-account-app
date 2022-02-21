package myapp.application.util.healthcheck

import akka.actor.ActorSystem
import akka.actor.typed.Scheduler
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.util.Timeout
import myapp.application.util.healthcheck.JDBCHealthCheckService.JDBCHealthCheckServiceKey

import scala.concurrent.{ ExecutionContext, Future }

class JDBCHealthCheck(system: ActorSystem) extends (() => Future[Boolean]) {
  private[this] val typedSystem                   = system.toTyped
  private[this] implicit val timeout: Timeout     = Timeout(new JDBCHealthCheckSetting(system.settings.config).timeout)
  private[this] implicit val scheduler: Scheduler = typedSystem.scheduler
  private[this] implicit val ec: ExecutionContext = typedSystem.executionContext

  override def apply(): Future[Boolean] = {
    for {
      JDBCHealthCheckServiceKey.Listing(listing) <-
        system.toTyped.receptionist ? (Receptionist.find[JDBCHealthCheckService.Command](JDBCHealthCheckServiceKey, _))
      jdbcHealthCheckService <- listing.find(_.ref.path.address.hasLocalScope) match {
        case Some(found) => Future.successful(found)
        case None        => Future.failed(new IllegalStateException("JDBCHealthCheckService is not ready"))
      }
      result <- (jdbcHealthCheckService ? JDBCHealthCheckService.GetCurrentStatus).map {
        case JDBCHealthCheckService.Healthy   => true
        case JDBCHealthCheckService.Unhealthy => false
      }
    } yield result
  }
}
