package org.http4s

import cats.effect.Effect
import io.circe.Json
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import cats.syntax.all._

class HelloWorldService[F[_]: Effect] extends Http4sDsl[F] {

  private def nameService: HttpService[F] = {
    HttpService[F] {
      case GET -> Root / "hello" / name =>
        Ok(Json.obj("message" -> Json.fromString(s"Hello, ${name}")))
    }
  }

  private def hiService: HttpService[F] = HttpService {
    case GET -> Root =>
      Ok("hi!")
  }

  def appRoutes: HttpService[F] = hiService <+> nameService
}
