package myapp.application.util.healthcheck

import akka.actor.CoordinatedShutdown

object JDBCHealthCheckFailureShutdown extends CoordinatedShutdown.Reason
