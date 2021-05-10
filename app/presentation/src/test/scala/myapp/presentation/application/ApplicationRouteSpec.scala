package myapp.presentation.application

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import lerna.testkit.airframe.DISessionSupport
import myapp.presentation.PresentationDIDesign
import myapp.utility.scalatest.StandardSpec
import wvlet.airframe.Design

@SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
class ApplicationRouteSpec extends StandardSpec with ScalatestRouteTest with DISessionSupport {
  override protected val diDesign: Design = PresentationDIDesign.presentationDesign

  val route: ApplicationRoute = diSession.build[ApplicationRoute]

  "ApplicationRoute" should {

    "reply OK" in {
      Get("/index") ~> route.route ~> check {
        expect {
          status === StatusCodes.OK
          responseAs[String] === "OK"
        }
      }
    }

  }

}
