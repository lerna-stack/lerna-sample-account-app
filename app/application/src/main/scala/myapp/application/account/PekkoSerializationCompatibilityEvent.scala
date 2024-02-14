package myapp.application.account

import org.apache.pekko
import pekko.actor.{ typed, ActorRef, Address }
import pekko.stream.SinkRef

import scala.concurrent.duration.FiniteDuration

sealed trait PekkoSerializationCompatibilityEvent
final case class PekkoActorRef(ref: ActorRef) extends PekkoSerializationCompatibilityEvent

final case class PekkoTypedActorRef[A](ref: typed.ActorRef[A]) extends PekkoSerializationCompatibilityEvent

final case class PekkoAddress(address: Address) extends PekkoSerializationCompatibilityEvent

final case class PekkoFiniteDuration(duration: FiniteDuration) extends PekkoSerializationCompatibilityEvent

final case class PekkoSinkRef[A](sinkRef: SinkRef[A]) extends PekkoSerializationCompatibilityEvent
