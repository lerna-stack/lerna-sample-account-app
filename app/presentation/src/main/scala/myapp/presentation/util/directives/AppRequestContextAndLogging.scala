package myapp.presentation.util.directives

import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.provide
import lerna.http.directives.RequestLogDirective
import myapp.utility.AppRequestContext

object AppRequestContextAndLogging extends AppRequestContextDirective with RequestLogDirective {

  val withAppRequestContextAndLogging: Directive1[AppRequestContext] = {
    extractAppRequestContext.flatMap { implicit appRequestContext =>
      logRequestDirective & logRequestResultDirective & provide(appRequestContext)
    }
  }

}
