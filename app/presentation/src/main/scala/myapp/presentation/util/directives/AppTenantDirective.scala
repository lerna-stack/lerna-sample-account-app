package myapp.presentation.util.directives
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ Directive1, MalformedHeaderRejection, MissingHeaderRejection }
import lerna.log.AppLogging
import myapp.utility.tenant.AppTenant

import scala.util.{ Failure, Success, Try }

object AppTenantDirective {
  private val headerName = "X-Tenant-Id"
}

trait AppTenantDirective extends AppLogging {

  import AppTenantDirective.headerName

  /** 以下の場合、HTTPリクエストは拒否される<br>
    * <li>テナント用HTTPヘッダーが無く、テナントが不明の場合</li>
    * <li>テナントIDが未知の値の場合 (Tenant class で受付可能な id を定義)</li>
    */
  private[directives] def extractTenantStrict: Directive1[AppTenant] = {
    extractTenant.flatMap { maybeTriedTenant =>
      import lerna.log.SystemComponentLogContext.logContext

      maybeTriedTenant match {
        case Some(Success(tenant)) =>
          provide(tenant)
        case Some(Failure(exception)) =>
          val message =
            s"""HTTP Header "$headerName" にテナントIDとして有効な値(${AppTenant.values.map(_.id).mkString(", ")})を指定してください。"""
          logger.warn(exception, message)
          reject(MalformedHeaderRejection(headerName, message, Option(exception)))
        case None =>
          logger.info(s"""HTTP Header "$headerName" が存在しません。Header を付与してください。""")
          reject(MissingHeaderRejection(headerName))
      }
    }
  }

  private[this] def extractTenant: Directive1[Option[Try[AppTenant]]] = {
    for {
      maybeTenantId <- optionalHeaderValueByName(headerName)
    } yield {
      maybeTenantId.map { id =>
        Try(AppTenant.withId(id))
      }
    }
  }
}
