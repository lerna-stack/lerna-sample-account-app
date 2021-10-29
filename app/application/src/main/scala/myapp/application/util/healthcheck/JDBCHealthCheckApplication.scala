package myapp.application.util.healthcheck

import myapp.readmodel.JDBCService
import myapp.utility.tenant.{ AppTenant, TenantA }

import scala.concurrent.Future

class JDBCHealthCheckApplication(jdbcService: JDBCService) {

  private[this] implicit val tenant: AppTenant = TenantA

  private[this] val profile = jdbcService.dbConfig.profile

  def check(): Future[Boolean] = {
    import profile.api._
    jdbcService.dbConfig.db.run(sql"SELECT 1".as[Boolean].head)
  }
}
