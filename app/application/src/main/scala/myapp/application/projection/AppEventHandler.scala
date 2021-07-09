package myapp.application.projection

import akka.actor.typed.{ ActorSystem, Behavior }
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.scaladsl.SourceProvider
import akka.projection.slick.{ SlickHandler, SlickProjection }
import akka.projection.{ Projection, ProjectionBehavior, ProjectionId }
import lerna.util.trace.TraceId
import myapp.utility.AppRequestContext
import myapp.utility.tenant.AppTenant
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

object AppEventHandler {
  final case class BehaviorSetup(
      system: ActorSystem[_],
      dbConfig: DatabaseConfig[JdbcProfile],
      tenant: AppTenant,
  )
}

trait AppEventHandler[E] extends SlickHandler[EventEnvelope[E]] {

  import AppEventHandler._

  protected def subscribeEventTag: String

  protected implicit def requestContext(implicit traceId: TraceId, tenant: AppTenant): AppRequestContext =
    AppRequestContext(traceId, tenant)

  def createBehavior(setup: BehaviorSetup): Behavior[ProjectionBehavior.Command] = {

    implicit val system: ActorSystem[_] = setup.system

    val sourceProvider: SourceProvider[Offset, EventEnvelope[E]] =
      EventSourcedProvider.eventsByTag[E](
        setup.system,
        readJournalPluginId = s"akka-entity-replication.eventsourced.persistence.cassandra-${setup.tenant.id}.query",
        tag = subscribeEventTag,
      )

    val projection: Projection[EventEnvelope[E]] =
      SlickProjection.exactlyOnce(
        projectionId = ProjectionId(subscribeEventTag, setup.tenant.id),
        sourceProvider = sourceProvider,
        setup.dbConfig,
        handler = () => this,
      )
    ProjectionBehavior(projection)
  }

}
