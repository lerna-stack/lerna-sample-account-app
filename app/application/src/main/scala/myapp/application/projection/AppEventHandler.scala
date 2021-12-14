package myapp.application.projection

import akka.actor.typed.{ ActorSystem, Behavior }
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.scaladsl.SourceProvider
import akka.projection.slick.{ SlickHandler, SlickProjection }
import akka.projection.{ Projection, ProjectionBehavior, ProjectionId }
import myapp.application.persistence.AggregateEventTag
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

  protected def eventTag: AggregateEventTag[E]

  def createBehavior(setup: BehaviorSetup): Behavior[ProjectionBehavior.Command] = {
    val sourceProvider: SourceProvider[Offset, EventEnvelope[E]] =
      EventSourcedProvider.eventsByTag[E](
        setup.system,
        readJournalPluginId = s"akka-entity-replication.eventsourced.persistence.cassandra-${setup.tenant.id}.query",
        tag = eventTag.tag,
      )

    ProjectionBehavior(createProjection(setup, sourceProvider))
  }

  def createProjection(
      setup: BehaviorSetup,
      sourceProvider: SourceProvider[Offset, EventEnvelope[E]],
  ): Projection[EventEnvelope[E]] = {
    implicit val system: ActorSystem[_] = setup.system
    SlickProjection.exactlyOnce(
      projectionId = ProjectionId(eventTag.tag, setup.tenant.id),
      sourceProvider = sourceProvider,
      setup.dbConfig,
      handler = () => this,
    )
  }
}
