package myapp.application.account

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit => ScalaTestWithAkkaActorTestKit}
import akka.serialization.{SerializationExtension => AkkaSerializationExtension}
import myapp.utility.scalatest.SpecAssertions
import org.apache.pekko
import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit => ScalaTestWithPekkoActorTestKit}
import org.apache.pekko.serialization.{SerializationExtension => PekkoSerializationExtension}
import org.scalatest.funsuite.AnyFunSuiteLike

import java.nio.charset.StandardCharsets

class AkkaSerializationTest extends ScalaTestWithAkkaActorTestKit() with SpecAssertions with AnyFunSuiteLike {

  import akka.actor.typed.scaladsl.adapter._

  test("Serialize ActorRef in Akka") {
    val akkaSerialization = AkkaSerializationExtension(system)
    val actorRef          = system.ref.toClassic
    val event             = AkkaSampleEvent(actorRef)
    val bytes             = akkaSerialization.serialize(event).get
    val serialized        = new String(bytes, StandardCharsets.UTF_8)
    expect(
      serialized === """{"ref":"akka://AkkaSerializationTest@26.255.0.5:25520/user"}""",
    )
//    val deserialized = akkaSerialization.deserialize(bytes, 9001, classOf[AkkaSampleEvent].getName).get
//    expect(
//      deserialized === event
//    )
  }
}

class PekkoDeserializationTest extends ScalaTestWithPekkoActorTestKit() with SpecAssertions with AnyFunSuiteLike {

  import org.apache.pekko.actor.typed.scaladsl.adapter._

  test("Deserialize ActorRef which is serialized by Akka in Pekko") {
    val pekkoSerializationExtension = PekkoSerializationExtension(system)
    val serialized                  = """{"ref":"akka://AkkaSerializationTest@26.255.0.4:25520/user"}"""
    val deserialized =
      pekkoSerializationExtension
        .deserialize(serialized.getBytes(StandardCharsets.UTF_8), 9002, classOf[PekkoSampleEvent].getName).get
    expect(
      deserialized === PekkoSampleEvent(system.deadLetters.toClassic),
    )
  }
}
