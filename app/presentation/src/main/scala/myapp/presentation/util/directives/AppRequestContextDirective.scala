package myapp.presentation.util.directives

import akka.http.scaladsl.server.Directive1
import lerna.http.directives.GenTraceIDDirective
import myapp.utility.AppRequestContext

trait AppRequestContextDirective extends GenTraceIDDirective with AppTenantDirective {
  protected def extractAppRequestContext: Directive1[AppRequestContext] =
    for {
      traceId <- extractTraceId
      tenant  <- extractTenantStrict
    } yield {
      AppRequestContext(traceId, tenant)
    }
}
