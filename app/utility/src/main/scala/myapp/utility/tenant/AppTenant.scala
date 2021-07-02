package myapp.utility.tenant

import lerna.util.lang.Equals._
import myapp.utility.AppRequestContext

trait AppTenant extends lerna.util.tenant.Tenant

object AppTenant {
  // テナントを追加したときは人力による追加が必要
  val values: Seq[AppTenant] = Seq[AppTenant](
    TenantA,
    TenantB,
  )

  def withId(id: String): AppTenant = values.find(_.id === id).getOrElse {
    throw new NoSuchElementException(s"No Tenant found for '$id'")
  }

  implicit def tenant(implicit appRequestContext: AppRequestContext): AppTenant = appRequestContext.tenant
}

sealed abstract class TenantA extends AppTenant
case object TenantA extends TenantA {
  override def id: String = "tenant-a"
}

sealed abstract class TenantB extends AppTenant
case object TenantB extends TenantB {
  override def id: String = "tenant-b"
}
