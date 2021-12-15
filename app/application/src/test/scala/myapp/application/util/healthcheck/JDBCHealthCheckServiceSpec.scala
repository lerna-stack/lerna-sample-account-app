package myapp.application.util.healthcheck

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import com.typesafe.config.{ Config, ConfigFactory }
import lerna.testkit.airframe.DISessionSupport
import lerna.testkit.akka.ScalaTestWithTypedActorTestKit
import myapp.utility.scalatest.StandardSpec
import org.scalamock.function.MockFunction0
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import wvlet.airframe.{ newDesign, Design }

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

@SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
class JDBCHealthCheckServiceSpec
    extends ScalaTestWithTypedActorTestKit
    with StandardSpec
    with BeforeAndAfterAll
    with MockFactory
    with DISessionSupport {
  import JDBCHealthCheckService._

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.Equals",
      "org.wartremover.warts.Null",
      "org.wartremover.warts.Throw",
    ),
  )
  override protected val diDesign: Design = newDesign
    .bind[Config].toInstance {
      ConfigFactory
        .parseString {
          """
          myapp.application.util.healthcheck.jdbc {
            interval = 1ms
            healthy-threshold = 1
            unhealthy-threshold = 1
          }
          """
        }.withFallback(testKit.config)
    }
    .bind[JDBCHealthCheckApplication].toInstance(mock[JDBCHealthCheckApplication])

  val mockApp: JDBCHealthCheckApplication     = diSession.build[JDBCHealthCheckApplication]
  val service: JDBCHealthCheckService         = diSession.build[JDBCHealthCheckService]
  val probe: TestProbe[GetCurrentStatusReply] = testKit.createTestProbe[GetCurrentStatusReply]()
  val check: MockFunction0[Future[Boolean]]   = mockApp.check _

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
    super.afterAll()
  }

  "JDBCHealthCheckService" should {
    "return Healthy when it succeeded in db connection" in {
      check.expects().returns(Future.successful(true)).anyNumberOfTimes()
      val healthChecker: ActorRef[Command] = testKit.spawn(service.createBehavior())
      probe.awaitAssert(
        {
          healthChecker ! GetCurrentStatus(probe.ref)
          probe.expectMessage(Healthy)
        },
        1000.millis,
        100.millis,
      )
    }

    "return Unhealthy when it failed to db connection" in {
      check.expects().returns(Future.successful(false)).anyNumberOfTimes()
      val healthChecker: ActorRef[Command] = testKit.spawn(service.createBehavior())
      probe.awaitAssert(
        {
          healthChecker ! GetCurrentStatus(probe.ref)
          probe.expectMessage(Unhealthy)
        },
        1000.millis,
        100.millis,
      )
    }
  }
}
