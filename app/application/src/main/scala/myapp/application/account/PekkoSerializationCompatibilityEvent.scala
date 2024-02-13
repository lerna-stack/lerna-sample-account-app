package myapp.application.account

import org.apache.pekko.actor.ActorRef

import scala.concurrent.duration.FiniteDuration

sealed trait PekkoSerializationCompatibilityEvent
final case class PekkoActorRef(ref: ActorRef) extends PekkoSerializationCompatibilityEvent

final case class PekkoFiniteDuration(duration: FiniteDuration) extends PekkoSerializationCompatibilityEvent
