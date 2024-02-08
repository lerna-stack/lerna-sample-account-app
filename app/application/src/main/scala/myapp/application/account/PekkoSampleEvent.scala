package myapp.application.account

import org.apache.pekko.actor.ActorRef

final case class PekkoSampleEvent(ref: ActorRef)
