package myapp.presentation.application.protocol

import myapp.adapter.Comment
import spray.json.{ DeserializationException, JsString, JsValue, RootJsonReader }

final case class CommentRequestBody(comment: Comment)

object CommentRequestBody {
  implicit object CommentRequestBodyFormat extends RootJsonReader[CommentRequestBody] {
    override def read(json: JsValue): CommentRequestBody = json.asJsObject.getFields("comment") match {
      case Seq(JsString(comment)) => CommentRequestBody(Comment(comment))
      case _                      => throw DeserializationException("received unexpected json object")
    }
  }
}
