package valinor.lib

import akka.util.ByteString
import io.bullet.borer.compat.akka.*
import io.bullet.borer.Codec
import io.bullet.borer.derivation.MapBasedCodecs.*
import io.bullet.borer.NullOptions.*

// websocket over http
@SuppressWarnings(Array("org.wartremover.warts.Null", "org.wartremover.warts.Var"))
object WebsocketOverHttp {
  case class Request(subscriptions: Vector[String], headers: Vector[Vector[String]], messages: Vector[ClientMessage])

  case class Response(messages: Vector[ControlMessage])

  case class Publication(messages: Vector[SendMessage])

  case class ClientMessage(text: String)

  case class ControlMessage(
    subscribe: Option[String]   = None,
    unsubscribe: Option[String] = None,
    send: Option[SendMessage]   = None
  )

  case class SendMessage(
    channel: Option[String]    = None,
    text: Option[String]       = None,
    binary: Option[ByteString] = None
  )

  object ControlMessage {
    val empty: ControlMessage = ControlMessage(None, None, None)
  }

  implicit val sendMessageCodec: Codec[SendMessage]       = deriveCodec
  implicit val controlMessageCodec: Codec[ControlMessage] = deriveCodec
  implicit val clientMessageCodec: Codec[ClientMessage]   = deriveCodec
  implicit val publicationCodec: Codec[Publication]       = deriveCodec
  implicit val responseCodec: Codec[Response]             = deriveCodec
  implicit val requestCodec: Codec[Request]               = deriveCodec
}
