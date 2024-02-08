package myapp.application.account

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.adapter._
import akka.serialization.SerializationExtension
import myapp.utility.scalatest.SpecAssertions
import org.scalatest.funsuite.AnyFunSuiteLike

import java.nio.charset.StandardCharsets

class SerializationCompatibilityTest extends ScalaTestWithActorTestKit() with SpecAssertions with AnyFunSuiteLike {
  test("Compatibility of Akka ActorRef and Pekko ActorRef") {
    val serialization = SerializationExtension(system)
    val actorRef = system.ref.toClassic
    val event = AkkaSampleEvent(actorRef)
    val bytes = serialization.serialize(event).get
    val serialized = new String(bytes, StandardCharsets.UTF_8)
    expect(
      serialized === ""
    )
  }

}
