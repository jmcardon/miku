package org.http4s

import cats.effect.{Effect, IO}
import cats.syntax.all._
import fs2.{Scheduler, StreamApp}
import org.http4s.miku.MikuBuilder

import scala.concurrent.ExecutionContext.Implicits.global

object Natto extends StreamApp[IO] {

  def stream(args: List[String], requestShutdown: IO[Unit]): fs2.Stream[IO, StreamApp.ExitCode] =
    for {
      sc    <- Scheduler[IO](1)
      serve <- MikuBuilder[IO].mountService(new HelloWorldService[IO](sc).appRoutes).withWebSockets(true).serve
    } yield serve

}
