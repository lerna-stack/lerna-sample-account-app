package myapp.application.account

import akka.actor.testkit.typed.scaladsl.{ ScalaTestWithActorTestKit => ScalaTestWithAkkaActorTestKit }
import akka.serialization.{ SerializationExtension => AkkaSerializationExtension }
import myapp.utility.scalatest.SpecAssertions
import org.apache.pekko.actor.testkit.typed.scaladsl.{ ScalaTestWithActorTestKit => ScalaTestWithPekkoActorTestKit }
import org.apache.pekko.serialization.{ Serialization, SerializationExtension => PekkoSerializationExtension }
import org.scalatest.funsuite.AnyFunSuiteLike

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.DurationInt

class SerializationCompatibilityTest
class AkkaSerializationTest extends ScalaTestWithAkkaActorTestKit() with SpecAssertions with AnyFunSuiteLike {

  import akka.actor.typed.scaladsl.adapter._

  test("Serialize ActorRef in Akka") {
    val akkaSerialization = AkkaSerializationExtension(system)
    val actorRef          = system.ref.toClassic
    val event             = AkkaActorRef(actorRef)
    val bytes             = akkaSerialization.serialize(event).get
    val serialized        = new String(bytes, StandardCharsets.UTF_8)
    println(serialized)
    expect(
      serialized === """{"ref":"akka://AkkaSerializationTest@26.255.0.4:25520/user"}""",
    )
//    val deserialized = akkaSerialization.deserialize(bytes, 9001, classOf[AkkaSampleEvent].getName).get
//    expect(
//      deserialized === event
//    )
  }

  test("Serialize typed ActorRef in Akka") {
    val akkaSerialization = AkkaSerializationExtension(system)
    val actorRef          = system.ref
    val event             = AkkaTypedActorRef(actorRef)
    val bytes             = akkaSerialization.serialize(event).get
    val serialized        = new String(bytes, StandardCharsets.UTF_8)
    println(serialized)
    expect(
      serialized === """{"ref":"akka://AkkaSerializationTest@26.255.0.4:25520/user"}""",
    )
  }

  test("Serialize FiniteDuration in Akka") {
    val akkaSerializationExtension = AkkaSerializationExtension(system)
    val finiteDuration             = 42.nanos + 42.micros + 42.millis + 42.seconds + 42.minutes + 42.hours
    val event                      = AkkaFiniteDuration(finiteDuration)
    val bytes                      = akkaSerializationExtension.serialize(event).get
    val serialized                 = new String(bytes, StandardCharsets.UTF_8)
    println(serialized)
    expect(serialized === """{"duration":"PT42H42M42.042042042S"}""")
  }
}

class PekkoDeserializationTest extends ScalaTestWithPekkoActorTestKit() with SpecAssertions with AnyFunSuiteLike {

  import org.apache.pekko.actor.typed.scaladsl.adapter._

  private val pekkoSerializationExtension = PekkoSerializationExtension(system)

  test("Deserialize ActorRef which is serialized by Akka in Pekko") {
    val serialized = """{"ref":"akka://AkkaSerializationTest@26.255.0.4:25520/user"}"""
    val deserialized =
      pekkoSerializationExtension
        .deserialize(serialized.getBytes(StandardCharsets.UTF_8), 9002, classOf[PekkoActorRef].getName).get
    expect(
      deserialized === PekkoActorRef(system.deadLetters.toClassic),
    )
  }

  test("Deserialize typed ActorRef which is serialized by Akka in Pekko") {
    val serialized = """{"ref":"akka://AkkaSerializationTest@26.255.0.4:25520/user"}"""
    val deserialized =
      pekkoSerializationExtension
        .deserialize(
          serialized.getBytes(StandardCharsets.UTF_8),
          9002,
          classOf[PekkoTypedActorRef[Nothing]].getName,
        ).get
    expect(
      deserialized === PekkoTypedActorRef(system.deadLetters),
    )
  }

  test("Deserialize FiniteDuration which is serialized by Akka in Pekko") {
    val serialized = """{"duration":"PT42H42M42.042042042S"}"""
    val deserialized = pekkoSerializationExtension
      .deserialize(serialized.getBytes(StandardCharsets.UTF_8), 9002, classOf[PekkoFiniteDuration].getName).get
    println(deserialized)
    val finiteDuration = 42.nanos + 42.micros + 42.millis + 42.seconds + 42.minutes + 42.hours
    expect(deserialized === PekkoFiniteDuration(finiteDuration))
  }
}
