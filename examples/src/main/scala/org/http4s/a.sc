import cats.effect.{IO, LiftIO}
import cats.syntax.all._


IO.async[Unit] { cb =>
  var i = 0
  while(i < 1000000)
    i +=1
  cb(Right(println("LOL1")))
}.unsafeRunAsync(_ => ())
println("LOL")