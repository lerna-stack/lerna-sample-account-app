package myapp.presentation.util.directives

import akka.http.scaladsl.server.Directive1
import lerna.http.directives.GenTraceIDDirective
import myapp.utility.AppRequestContext
import myapp.utility.tenant.TenantA

trait AppRequestContextDirective extends GenTraceIDDirective {
  protected def extractAppRequestContext: Directive1[AppRequestContext] =
    for {
      traceId <- extractTraceId
    } yield {
      AppRequestContext(traceId, TenantA)
    }
}
