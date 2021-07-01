package myapp.utility

import lerna.util.tenant.Tenant
import lerna.util.trace.TraceId

final case class AppRequestContext(traceId: TraceId) extends lerna.util.trace.RequestContext {
  override implicit def tenant: Tenant = new Tenant {
    override def id: String = "nothing"
  }
}
