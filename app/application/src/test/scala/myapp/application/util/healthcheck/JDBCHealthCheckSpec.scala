package myapp.application.util.healthcheck

import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import com.typesafe.config.ConfigFactory
import lerna.testkit.akka.ScalaTestWithTypedActorTestKit
import myapp.application.util.healthcheck.JDBCHealthCheckService.{
  Command,
  GetCurrentStatus,
  Healthy,
  JDBCHealthCheckServiceKey,
  Unhealthy,
}
import myapp.utility.scalatest.StandardSpec
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration.DurationInt

class JDBCHealthCheckSpec extends ScalaTestWithTypedActorTestKit with StandardSpec {
  private val config  = ConfigFactory.parseString("myapp.application.util.healthcheck.jdbc.timeout = 500ms")
  private val setting = new JDBCHealthCheckSetting(config)

  def healthyMock: Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      case GetCurrentStatus(replyTo) =>
        replyTo ! Healthy
        Behaviors.same
      case _ => Behaviors.same
    }
  def unhealthyMock: Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      case GetCurrentStatus(replyTo) =>
        replyTo ! Unhealthy
        Behaviors.same
      case _ => Behaviors.same
    }

  "JDBCHealthCheck" should {
    "return true if the health check has succeeded" in {
      val actor = testKit.spawn[Command](healthyMock)
      system.receptionist ! Receptionist.register(JDBCHealthCheckServiceKey, actor)
      val probe = testKit.createTestProbe()
      val isHealthy =
        probe.awaitAssert((new JDBCHealthCheck(system.toClassic, setting))().futureValue, 1000.millis, 100.millis)
      expect(isHealthy === true)
      system.receptionist ! Receptionist.deregister(JDBCHealthCheckServiceKey, actor)
    }

    "return false if the health check has failed" in {
      val actor = testKit.spawn[Command](unhealthyMock)
      system.receptionist ! Receptionist.register(JDBCHealthCheckServiceKey, actor)
      val probe = testKit.createTestProbe()
      val isHealthy =
        probe.awaitAssert((new JDBCHealthCheck(system.toClassic, setting))().futureValue, 1000.millis, 100.millis)
      expect(isHealthy === false)
      system.receptionist ! Receptionist.deregister(JDBCHealthCheckServiceKey, actor)
    }

    "throw IllegalStateException when the JDBCHealthCheckService is not found" in {
      val future = (new JDBCHealthCheck(system.toClassic, setting))()
      ScalaFutures.whenReady(future.failed) { e =>
        e shouldBe an[IllegalStateException]
      }
    }
  }
}
