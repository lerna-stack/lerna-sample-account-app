package myapp.application.serialize

import io.altoo.akka.serialization.kryo.DefaultKryoInitializer
import io.altoo.akka.serialization.kryo.serializer.scala.ScalaKryo

trait KryoSerializable

object KryoSerializable {
  class KryoInitializer extends DefaultKryoInitializer {
    override def preInit(kryo: ScalaKryo): Unit = {
      kryo.setDefaultSerializer(classOf[com.esotericsoftware.kryo.serializers.CompatibleFieldSerializer[_]])
    }
  }
}
