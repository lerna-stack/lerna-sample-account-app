package myapp.application.account

import org.apache.pekko
import pekko.actor.{ActorRef, Address, typed}
import pekko.stream.{SinkRef, SourceRef}

import scala.concurrent.duration.FiniteDuration

sealed trait PekkoSerializationCompatibilityEvent
final case class PekkoActorRef(ref: ActorRef) extends PekkoSerializationCompatibilityEvent

final case class PekkoTypedActorRef[A](ref: typed.ActorRef[A]) extends PekkoSerializationCompatibilityEvent

final case class PekkoAddress(address: Address) extends PekkoSerializationCompatibilityEvent

final case class PekkoFiniteDuration(duration: FiniteDuration) extends PekkoSerializationCompatibilityEvent

final case class PekkoSinkRef[A](sinkRef: SinkRef[A]) extends PekkoSerializationCompatibilityEvent

final case class PekkoSourceRef[A](sourceRef: SourceRef[A]) extends PekkoSerializationCompatibilityEvent
