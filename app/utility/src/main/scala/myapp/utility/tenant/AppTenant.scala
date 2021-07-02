package myapp.utility.tenant

import com.fasterxml.jackson.core.{ JsonGenerator, JsonParser }
import com.fasterxml.jackson.databind.{ DeserializationContext, SerializerProvider }
import com.fasterxml.jackson.databind.annotation.{ JsonDeserialize, JsonSerialize }
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import lerna.util.lang.Equals._
import myapp.utility.AppRequestContext

@JsonSerialize(using = classOf[AppTenant.JsonSerializer])
@JsonDeserialize(using = classOf[AppTenant.JsonDeserializer])
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

  private[tenant] class JsonSerializer extends StdSerializer[AppTenant](classOf[AppTenant]) {
    override def serialize(tenant: AppTenant, jsonGenerator: JsonGenerator, provider: SerializerProvider): Unit = {
      jsonGenerator.writeString(tenant.id)
    }
  }

  private[tenant] class JsonDeserializer extends StdDeserializer[AppTenant](classOf[AppTenant]) {
    override def deserialize(p: JsonParser, context: DeserializationContext): AppTenant = AppTenant.withId(p.getText)
  }

}

sealed abstract class TenantA extends AppTenant
case object TenantA extends TenantA {
  override def id: String = "tenant-a"
}

sealed abstract class TenantB extends AppTenant
case object TenantB extends TenantB {
  override def id: String = "tenant-b"
}
