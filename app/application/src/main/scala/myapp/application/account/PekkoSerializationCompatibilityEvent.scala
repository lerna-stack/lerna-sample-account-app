package myapp.application.account

import org.apache.pekko
import pekko.actor.ActorRef
import pekko.actor.typed

import scala.concurrent.duration.FiniteDuration

sealed trait PekkoSerializationCompatibilityEvent
final case class PekkoActorRef(ref: ActorRef) extends PekkoSerializationCompatibilityEvent

final case class PekkoTypedActorRef[A](ref: typed.ActorRef[A]) extends PekkoSerializationCompatibilityEvent

final case class PekkoFiniteDuration(duration: FiniteDuration) extends PekkoSerializationCompatibilityEvent
