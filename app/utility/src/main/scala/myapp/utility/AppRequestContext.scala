package myapp.utility

import lerna.util.trace.TraceId
import myapp.utility.tenant.{ AppTenant, TenantA }

final case class AppRequestContext(traceId: TraceId) extends lerna.util.trace.RequestContext {
  override implicit def tenant: AppTenant = TenantA
}
