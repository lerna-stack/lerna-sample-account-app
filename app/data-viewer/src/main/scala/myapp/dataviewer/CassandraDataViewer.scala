package myapp.dataviewer

import akka.actor.ClassicActorSystemProvider
import akka.serialization.SerializationExtension
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.{ CassandraSession, CassandraSessionRegistry }
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.protocol.internal.util.Bytes

import scala.concurrent.Future
import scala.util.{ Failure, Success }

trait DataViewer {
  import DataViewer._

  def fetch(persistenceType: PersistenceType): Source[(SequenceNr, Data), NotUsed]
  def close(): Future[Done]
}

object DataViewer {
  type Data       = AnyRef
  type SequenceNr = Long

  sealed trait PersistenceType

  object PersistenceType {
    final case class Event(
        persistenceId: String,
        fromSequenceNr: SequenceNr,
        toSequenceNr: SequenceNr,
    ) extends PersistenceType

    final case class Snapshot(
        persistenceId: String,
        sequenceNr: SequenceNr,
    ) extends PersistenceType
  }
}

class CassandraDataViewer(configPath: String)(implicit system: ClassicActorSystemProvider) extends DataViewer {
  import DataViewer._

  private val serialization = SerializationExtension(system)

  private val session: CassandraSession =
    CassandraSessionRegistry(system).sessionFor(CassandraSessionSettings(configPath))

  private val classicSystem = system.classicSystem
  import classicSystem.dispatcher

  override def close(): Future[Done] = session.close(dispatcher)

  private val journalConfig  = classicSystem.settings.config.getConfig(configPath).getConfig("journal")
  private val queryConfig    = classicSystem.settings.config.getConfig(configPath).getConfig("query")
  private val snapshotConfig = classicSystem.settings.config.getConfig(configPath).getConfig("snapshot")

  private val targetPartitionSize = journalConfig.getLong("target-partition-size")

  private val preparedSelectEventsByPersistenceId: Future[PreparedStatement] = {
    val keyspace = journalConfig.getString("keyspace")
    val table    = journalConfig.getString("table")
    val query =
      s"""
      SELECT * FROM $keyspace.$table WHERE
        persistence_id = ? AND
        partition_nr = ? AND
        sequence_nr >= ? AND
        sequence_nr <= ?
    """

    session.prepare(query)
  }

  private val preparedSelectSnapshotByPersistenceId: Future[PreparedStatement] = {
    val keyspace = snapshotConfig.getString("keyspace")
    val table    = snapshotConfig.getString("table")
    val query =
      s"""
        SELECT * FROM $keyspace.$table WHERE
          persistence_id = ? AND
          sequence_nr = ?
      """

    session.prepare(query)
  }

  def waitForSetup: Future[Done.type] =
    Future
      .sequence(
        Seq(
          preparedSelectEventsByPersistenceId,
          preparedSelectSnapshotByPersistenceId,
        ),
      ).map(_ => Done)

  private def dataColumnNameOf(persistenceType: PersistenceType) = persistenceType match {
    case _: PersistenceType.Event    => "event"
    case _: PersistenceType.Snapshot => "snapshot_data"
  }

  override def fetch(persistenceType: PersistenceType): Source[(SequenceNr, Data), NotUsed] = {
    val future: Future[Source[(SequenceNr, Data), NotUsed]] = for {
      boundStatement <- {
        import java.lang.{ Long => JLong }
        persistenceType match {
          case event: PersistenceType.Event =>
            val fromSequenceNr = event.fromSequenceNr
            val partitionNr    = (fromSequenceNr - 1L) / targetPartitionSize
            preparedSelectEventsByPersistenceId.map { preparedStatement =>
              preparedStatement
                .bind(event.persistenceId, partitionNr: JLong, fromSequenceNr: JLong, event.toSequenceNr: JLong)
                .setExecutionProfileName(queryConfig.getString("read-profile"))
            }
          case snapshot: PersistenceType.Snapshot =>
            preparedSelectSnapshotByPersistenceId.map { preparedStatement =>
              preparedStatement
                .bind(snapshot.persistenceId, snapshot.sequenceNr: JLong)
                .setExecutionProfileName(snapshotConfig.getString("read-profile"))
            }
        }
      }
    } yield {
      session
        .select(boundStatement)
        .map { row =>
          val sequenceNr = row.getLong("sequence_nr")
          val bytes      = Bytes.getArray(row.getByteBuffer(dataColumnNameOf(persistenceType)))
          val serId      = row.getInt("ser_id")
          val manifest   = row.getString("ser_manifest")

          serialization.deserialize(bytes, serId, manifest) match {
            case Success(event)     => (sequenceNr, event)
            case Failure(exception) => throw exception
          }
        }
    }

    Source.futureSource(future).mapMaterializedValue(_ => NotUsed)
  }
}
