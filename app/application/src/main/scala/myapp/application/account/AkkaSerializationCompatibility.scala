package myapp.application.account

import akka.actor.{ typed, ActorRef, Address }
import akka.stream.SinkRef

import scala.concurrent.duration.FiniteDuration

sealed trait AkkaSerializationCompatibilityEvent
final case class AkkaActorRef(ref: ActorRef) extends AkkaSerializationCompatibilityEvent

final case class AkkaTypedActorRef[A](ref: typed.ActorRef[A]) extends AkkaSerializationCompatibilityEvent

final case class AkkaAddress(address: Address) extends AkkaSerializationCompatibilityEvent

final case class AkkaFiniteDuration(duration: FiniteDuration) extends AkkaSerializationCompatibilityEvent

final case class AkkaSinkRef[A](sinkRef: SinkRef[A]) extends AkkaSerializationCompatibilityEvent
