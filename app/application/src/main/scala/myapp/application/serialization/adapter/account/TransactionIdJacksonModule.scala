package myapp.application.serialization.adapter.account

import com.fasterxml.jackson.core.{ JsonGenerator, JsonParser }
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.databind.{ DeserializationContext, SerializerProvider }
import myapp.adapter.account.TransactionId

class TransactionIdJacksonModule extends SimpleModule {
  import TransactionIdJacksonModule._

  addSerializer(new JsonSerializer)
  addDeserializer(classOf[TransactionId], new JsonDeserializer)
}

object TransactionIdJacksonModule {
  private class JsonSerializer extends StdSerializer[TransactionId](classOf[TransactionId]) {
    override def serialize(
        transactionId: TransactionId,
        jsonGenerator: JsonGenerator,
        provider: SerializerProvider,
    ): Unit = {
      jsonGenerator.writeNumber(transactionId.value)
    }
  }

  private class JsonDeserializer extends StdDeserializer[TransactionId](classOf[TransactionId]) {
    override def deserialize(p: JsonParser, context: DeserializationContext): TransactionId =
      TransactionId(p.getValueAsString)
  }
}
