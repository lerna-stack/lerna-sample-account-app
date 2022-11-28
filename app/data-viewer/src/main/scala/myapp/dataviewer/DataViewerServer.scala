package myapp.dataviewer

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.common.EntityStreamingSupport
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directives
import akka.stream.scaladsl.{ Flow, Source }
import akka.util.ByteString
import spray.json.{ DefaultJsonProtocol, JsString, JsValue, JsonFormat }

import java.util.concurrent.ConcurrentHashMap

object DataViewerServer extends App with Directives with SprayJsonSupport with DefaultJsonProtocol {
  import DataViewer._

  private implicit val system: ActorSystem = ActorSystem()

  private type ConfigPath = String

  private val cassandraDataViewerMap = new ConcurrentHashMap[ConfigPath, CassandraDataViewer]

  private def pathToCassandraDataViewer(configPath: ConfigPath) =
    cassandraDataViewerMap.computeIfAbsent(configPath, path => new CassandraDataViewer(path))

  private implicit val streamingSupport: EntityStreamingSupport = EntityStreamingSupport
    .json()
    .withFramingRenderer(Flow[ByteString].intersperse(ByteString("[\n\t"), ByteString(",\n\t"), ByteString("\n]")))

  private implicit val format: JsonFormat[Data] = new JsonFormat[Data] {
    override def write(obj: Data): JsValue = JsString(obj.toString) // Data = AnyRef の json 変換定義が無いため 一律で String に変換する
    override def read(json: JsValue): Data = ???                    // Not used when responding
  }

  val bindingRoute = pathPrefix(Segment) { configPath =>
    concat(
      path("event" / Segment / LongNumber) { (persistenceId, fromSequenceNr) =>
        parameter("to".as[Long].withDefault(fromSequenceNr)) { toSequenceNr =>
          val persistenceType                             = DataViewer.PersistenceType.Event(persistenceId, fromSequenceNr, toSequenceNr)
          val source: Source[(SequenceNr, Data), NotUsed] = pathToCassandraDataViewer(configPath).fetch(persistenceType)
          complete(source)
        }
      },
      path("snapshot" / Segment / LongNumber) { (persistenceId, sequenceNr) =>
        val persistenceType                             = DataViewer.PersistenceType.Snapshot(persistenceId, sequenceNr)
        val source: Source[(SequenceNr, Data), NotUsed] = pathToCassandraDataViewer(configPath).fetch(persistenceType)
        complete(source)
      },
    )
  }

  private val config = system.settings.config
  val interface      = config.getString("myapp.data-viewer.server.interface")
  val port           = config.getInt("myapp.data-viewer.server.port")

  Http()
    .newServerAt(interface, port)
    .bindFlow(bindingRoute)
}
