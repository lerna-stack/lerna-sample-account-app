package myapp.application.account

import akka.actor.ActorRef
import akka.actor.typed

import scala.concurrent.duration.FiniteDuration

sealed trait AkkaSerializationCompatibilityEvent
final case class AkkaActorRef(ref: ActorRef) extends AkkaSerializationCompatibilityEvent

final case class AkkaTypedActorRef[A](ref: typed.ActorRef[A]) extends AkkaSerializationCompatibilityEvent

final case class AkkaFiniteDuration(duration: FiniteDuration) extends AkkaSerializationCompatibilityEvent
