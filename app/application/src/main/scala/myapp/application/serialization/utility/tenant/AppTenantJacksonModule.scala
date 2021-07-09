package myapp.application.serialization.utility.tenant

import com.fasterxml.jackson.core.{ JsonGenerator, JsonParser }
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import myapp.utility.tenant.AppTenant

class AppTenantJacksonModule extends SimpleModule {
  import AppTenantJacksonModule._

  addSerializer(new JsonSerializer)
  addDeserializer(classOf[AppTenant], new JsonDeserializer)
}

object AppTenantJacksonModule {
  private class JsonSerializer extends StdSerializer[AppTenant](classOf[AppTenant]) {
    override def serialize(tenant: AppTenant, jsonGenerator: JsonGenerator, provider: SerializerProvider): Unit = {
      jsonGenerator.writeString(tenant.id)
    }
  }

  private class JsonDeserializer extends StdDeserializer[AppTenant](classOf[AppTenant]) {
    override def deserialize(p: JsonParser, context: DeserializationContext): AppTenant = AppTenant.withId(p.getText)
  }
}
