package myapp.presentation.util.directives

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.{ MalformedHeaderRejection, MissingHeaderRejection, Route }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.Inside
import myapp.utility.scalatest.StandardSpec
import myapp.utility.tenant.TenantA

class AppTenantDirectiveSpec extends StandardSpec with ScalatestRouteTest with Inside {
  private val tenantHeaderName = "X-Tenant-Id"

  private def tenantHeader(value: String): RawHeader = RawHeader(tenantHeaderName, value)

  private val testRoute = new AppTenantDirectiveSpec.TestRoute

  "extractTenantStrict" should {
    val route = testRoute.strictRoute

    "テナントHeaderが存在する（存在するテナント）" when {
      val request = Get("/").withHeaders(tenantHeader(TenantA.id))
      "テナントを得られる" in {
        request ~> route ~> check {
          expect {
            handled
            responseAs[String] === TenantA.id
          }
        }
      }
    }

    "テナントHeaderが存在する（存在しないテナント）" when {
      val request = Get("/").withHeaders(tenantHeader("__dummy__"))
      "ログ出力＆rejectされる" in {
        request ~> route ~> check {
          // ログは目視で確認
          inside(rejection) {
            case malformedHeaderRejection: MalformedHeaderRejection =>
              expect(malformedHeaderRejection.headerName === tenantHeaderName)
          }
        }
      }
    }

    "テナントHeaderが存在しない" when {
      val request = Get("/")
      "ログ出力＆rejectされる" in {
        request ~> route ~> check {
          // ログは目視で確認
          inside(rejection) {
            case MissingHeaderRejection(headerName) =>
              expect(headerName === tenantHeaderName)
          }
        }
      }
    }
  }
}

object AppTenantDirectiveSpec {
  private class TestRoute extends AppTenantDirective {
    val strictRoute: Route = extractTenantStrict { tenant =>
      complete(tenant.id)
    }
  }
}
