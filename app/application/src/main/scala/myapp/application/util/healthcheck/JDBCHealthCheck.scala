package myapp.application.util.healthcheck

import akka.actor.ActorSystem
import akka.actor.typed.Scheduler
import akka.actor.typed.receptionist.Receptionist

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import myapp.application.util.healthcheck.JDBCHealthCheckService.JDBCHealthCheckServiceKey

class JDBCHealthCheck(system: ActorSystem) extends (() => Future[Boolean]) {
  private[this] val typedSystem                   = system.toTyped
  private[this] implicit val timeout: Timeout     = Timeout(500.millis) // FIXME: 設定できるようにするか検討
  private[this] implicit val scheduler: Scheduler = typedSystem.scheduler
  private[this] implicit val ec: ExecutionContext = typedSystem.executionContext

  override def apply(): Future[Boolean] = {
    for {
      JDBCHealthCheckServiceKey.Listing(listing) <-
        system.toTyped.receptionist ? (Receptionist.find(JDBCHealthCheckServiceKey, _))
      jdbcHealthCheckService <- listing.headOption match {
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
