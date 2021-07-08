package myapp.application.serialization.utility.tenant

import com.fasterxml.jackson.core.{ JsonGenerator, JsonParser }
import com.fasterxml.jackson.databind
import com.fasterxml.jackson.databind.deser.Deserializers
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.Serializers
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.module.scala.JacksonModule
import myapp.utility.tenant.AppTenant

class AppTenantJacksonModule extends JacksonModule {
  import AppTenantJacksonModule._
  override def getModuleName(): String = this.getClass.getName

  +=(new Serializers.Base {
    private val serializer = new JsonSerializer
    override def findSerializer(
        config: SerializationConfig,
        javaType: JavaType,
        beanDesc: BeanDescription,
    ): databind.JsonSerializer[_] = {
      if (serializer.handledType().isAssignableFrom(javaType.getRawClass)) serializer
      else super.findSerializer(config, javaType, beanDesc)
    }
  })

  +=(new Deserializers.Base {
    private val deserializer = new JsonDeserializer

    override def findBeanDeserializer(
        javaType: JavaType,
        config: DeserializationConfig,
        beanDesc: BeanDescription,
    ): databind.JsonDeserializer[_] = {
      if (deserializer.handledType().isAssignableFrom(javaType.getRawClass)) deserializer
      else super.findBeanDeserializer(javaType, config, beanDesc)
    }
  })
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
