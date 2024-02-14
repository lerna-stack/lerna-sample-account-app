package myapp.application.account

import akka.actor.testkit.typed.scaladsl.{ ScalaTestWithActorTestKit => ScalaTestWithAkkaActorTestKit }
import akka.serialization.{ SerializationExtension => AkkaSerializationExtension }
import akka.stream.scaladsl.{ Sink, StreamRefs => AkkaStreamRefs }
import myapp.utility.scalatest.SpecAssertions
import org.apache.pekko.actor.Address
import org.apache.pekko.actor.testkit.typed.scaladsl.{ ScalaTestWithActorTestKit => ScalaTestWithPekkoActorTestKit }
import org.apache.pekko.serialization.{ SerializationExtension => PekkoSerializationExtension }
import org.apache.pekko.stream.impl.streamref.SinkRefImpl
import org.scalatest.funsuite.AnyFunSuiteLike

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.DurationInt

class SerializationCompatibilityTest
class AkkaSerializationTest extends ScalaTestWithAkkaActorTestKit() with SpecAssertions with AnyFunSuiteLike {

  import akka.actor.typed.scaladsl.adapter._

  private val akkaSerializationExtension = AkkaSerializationExtension(system)

  test("Serialize ActorRef in Akka") {
    val actorRef   = system.ref.toClassic
    val event      = AkkaActorRef(actorRef)
    val bytes      = akkaSerializationExtension.serialize(event).get
    val serialized = new String(bytes, StandardCharsets.UTF_8)
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
    val actorRef   = system.ref
    val event      = AkkaTypedActorRef(actorRef)
    val bytes      = akkaSerializationExtension.serialize(event).get
    val serialized = new String(bytes, StandardCharsets.UTF_8)
    println(serialized)
    expect(
      serialized === """{"ref":"akka://AkkaSerializationTest@26.255.0.4:25520/user"}""",
    )
  }

  test("Serialize Address in Akka") {
    val address    = system.address
    val event      = AkkaAddress(address)
    val bytes      = akkaSerializationExtension.serialize(event).get
    val serialized = new String(bytes, StandardCharsets.UTF_8)
    println(serialized)
    expect(serialized === """{"address":"akka://AkkaSerializationTest@26.255.0.5:25520"}""")
  }

  test("Serialize FiniteDuration in Akka") {
    val finiteDuration = 42.nanos + 42.micros + 42.millis + 42.seconds + 42.minutes + 42.hours
    val event          = AkkaFiniteDuration(finiteDuration)
    val bytes          = akkaSerializationExtension.serialize(event).get
    val serialized     = new String(bytes, StandardCharsets.UTF_8)
    println(serialized)
    expect(serialized === """{"duration":"PT42H42M42.042042042S"}""")
  }

  test("Serialize SinkRef in Akka") {
    val sinkRef    = AkkaStreamRefs.sinkRef().to(Sink.fold[String, String]("")(_ + _)).run()
    val event      = AkkaSinkRef(sinkRef)
    val bytes      = akkaSerializationExtension.serialize(event).get
    val serialized = new String(bytes, StandardCharsets.UTF_8)
    println(serialized)
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

  test("Deserialize address which is serialized by Akka in Pekko") {
    val serialized = """{"address":"akka://AkkaSerializationTest@26.255.0.5:25520"}"""
    val deserialized =
      pekkoSerializationExtension
        .deserialize(
          serialized.getBytes(StandardCharsets.UTF_8),
          9002,
          classOf[PekkoAddress].getName,
        ).get
    expect(
      deserialized === PekkoAddress(Address("akka", "AkkaSerializationTest", Some("26.255.0.5"), Some(25520))),
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

  test("Deserialize SinkRef which is serialized by Akka in Pekko") {
    val serialized =
      """{"sinkRef":"akka://AkkaSerializationTest@26.255.0.5:25520/system/Materializers/StreamSupervisor-0/$$a-SourceRef-0#-1784691012"}"""
    val deserialized = pekkoSerializationExtension
      .deserialize(serialized.getBytes(StandardCharsets.UTF_8), 9002, classOf[PekkoSinkRef[Nothing]].getName).get
    println(deserialized)
    expect(deserialized.toString === "PekkoSinkRef(SinkRefImpl(Actor[pekko://PekkoDeserializationTest/deadLetters]))")
  }
}
