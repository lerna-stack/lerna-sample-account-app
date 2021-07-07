package myapp.application.projection

import akka.actor.typed.{ ActorSystem, Behavior }
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.scaladsl.SourceProvider
import akka.projection.slick.{ SlickHandler, SlickProjection }
import akka.projection.{ Projection, ProjectionBehavior, ProjectionId }
import lerna.util.trace.TraceId
import myapp.readmodel.JDBCService
import myapp.utility.AppRequestContext
import myapp.utility.tenant.AppTenant

trait AppEventHandler[E] extends SlickHandler[EventEnvelope[E]] {

  implicit protected def system: ActorSystem[_]

  implicit protected def tenant: AppTenant

  protected def jdbcService: JDBCService

  protected def subscribeEventTag: String

  protected implicit def requestContext(implicit traceId: TraceId): AppRequestContext =
    AppRequestContext(traceId, tenant)

  def createBehavior(): Behavior[ProjectionBehavior.Command] = {

    val sourceProvider: SourceProvider[Offset, EventEnvelope[E]] =
      EventSourcedProvider.eventsByTag[E](
        system,
        readJournalPluginId = s"akka-entity-replication.eventsourced.persistence.cassandra-${tenant.id}.query",
        tag = subscribeEventTag,
      )

    val projection: Projection[EventEnvelope[E]] =
      SlickProjection.exactlyOnce(
        projectionId = ProjectionId(subscribeEventTag, tenant.id),
        sourceProvider = sourceProvider,
        jdbcService.dbConfig,
        handler = () => this,
      )
    ProjectionBehavior(projection)
  }

}
