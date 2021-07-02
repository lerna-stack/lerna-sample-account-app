package myapp.utility

import lerna.util.trace.TraceId
import myapp.utility.tenant.AppTenant

final case class AppRequestContext(traceId: TraceId, tenant: AppTenant) extends lerna.util.trace.RequestContext
