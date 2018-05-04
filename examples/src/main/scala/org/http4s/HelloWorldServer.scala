package org.http4s

import cats.effect.{Effect, IO}
import cats.syntax.all._
import fs2.StreamApp
import org.http4s.miku.MikuBuilder
import scala.concurrent.ExecutionContext.Implicits.global

object Natto extends StreamApp[IO] {

  def stream(args: List[String], requestShutdown: IO[Unit]): fs2.Stream[IO, StreamApp.ExitCode] =
    MikuBuilder[IO](new HelloWorldService[IO].appRoutes).serve
}
