package myapp.presentation.management

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.typesafe.config.{ Config, ConfigFactory }
import lerna.testkit.airframe.DISessionSupport
import myapp.presentation.PresentationDIDesign
import myapp.utility.scalatest.StandardSpec
import wvlet.airframe.Design

@SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
class VersionRouteSpec extends StandardSpec with ScalatestRouteTest with DISessionSupport {
  override protected val diDesign: Design = PresentationDIDesign.presentationDesign
    .bind[Config].toInstance(ConfigFactory.parseString("""
        |myapp.presentation.versions {
        | version = 0.1.0
        | commit-hash = 8653fe0c05bc1a88e2e5bc96892373e365ffc898
        |}
      """.stripMargin))

  val route: VersionRoute = diSession.build[VersionRoute]

  "VersionRoute" should {

    "設定されたバージョン番号を返す" in {
      Get("/version") ~> route.route ~> check {
        expect {
          status === StatusCodes.OK
          responseAs[String] === "0.1.0"
        }
      }
    }

    "設定されたコミットハッシュを返す" in {
      Get("/commit-hash") ~> route.route ~> check {
        expect {
          status === StatusCodes.OK
          responseAs[String] === "8653fe0c05bc1a88e2e5bc96892373e365ffc898"
        }
      }
    }
  }
}
