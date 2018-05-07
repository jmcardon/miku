package org.http4s

import cats.effect.Effect
import io.circe.Json
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import cats.syntax.all._
import fs2.{Scheduler, Sink, Stream}
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebsocketBits

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class HelloWorldService[F[_]: Effect](sc: Scheduler)(implicit ec: ExecutionContext) extends Http4sDsl[F] {

  private def nameService: HttpService[F] =
    HttpService[F] {
      case GET -> Root / "hello" / name =>
        Ok(Json.obj("message" -> Json.fromString(s"Hello, $name")))
    }

  private def hiService: HttpService[F] = HttpService {
    case GET -> Root =>
      Ok("hi!")
  }

  private def wsService: HttpService[F] = HttpService {
    case GET -> Root / "ws" =>
      val send: Stream[F, WebsocketBits.WebSocketFrame] =
        Stream.repeatEval(sc.effect.sleep(2.seconds).map(_ => WebsocketBits.Text("hi")))
      val receive: Sink[F, WebsocketBits.WebSocketFrame] = _.evalMap(e => Effect[F].delay(println(e)))
      WebSocketBuilder[F].build(send, receive)
  }

  def appRoutes: HttpService[F] = hiService <+> nameService <+> wsService
}
