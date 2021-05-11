package myapp.gateway

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{ HttpMethods, HttpRequest, Uri }
import akka.http.scaladsl.unmarshalling.Unmarshal
import lerna.http.json.AnyValJsonFormat
import myapp.adapter.{ Cursor, DepositPoolGateway }
import myapp.adapter.DepositPoolGateway._
import spray.json.{ CollectionFormats, DefaultJsonProtocol, JsonFormat, RootJsonFormat }

import scala.concurrent.Future

object DepositPoolGatewayImpl {

  trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol with CollectionFormats {
    implicit val cursorFormat: JsonFormat[Cursor]       = AnyValJsonFormat(Cursor.apply, Cursor.unapply)
    implicit val depositFormat: RootJsonFormat[Deposit] = jsonFormat3(Deposit)
    implicit val bodyFormat: RootJsonFormat[Body]       = jsonFormat1(Body)
  }
}

import DepositPoolGatewayImpl._

class DepositPoolGatewayImpl(gatewayConfig: GatewayConfig)(implicit system: ActorSystem)
    extends DepositPoolGateway
    with JsonSupport {

  import system.dispatcher

  override def fetch(cursor: Option[Cursor], limit: Int): Future[Body] = {
    val query: Query =
      ("limit" -> limit.toString) +: cursor.map(c => Query("cursor" -> c.cursor)).getOrElse(Query.Empty)
    val uri =
      Uri(s"http://${gatewayConfig.dataSourceSystemHost}:${gatewayConfig.dataSourceSystemPort}/data").withQuery(query)
    val response =
      Http().singleRequest(
        HttpRequest(
          method = HttpMethods.GET,
          uri,
        ),
      )
    for {
      resp <- response
      json <- Unmarshal(resp).to[Body]
    } yield json
  }
}
