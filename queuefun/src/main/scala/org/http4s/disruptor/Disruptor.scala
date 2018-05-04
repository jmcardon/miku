package org.http4s.disruptor

import cats.effect.Effect
import com.conversantmedia.util.concurrent.{DisruptorBlockingQueue => DBQ}
import fs2.{Chunk, Pipe, Stream}
import org.http4s.queue.Queue

import scala.collection.mutable.ListBuffer
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

object Disruptor {

  def bounded[F[_], A](qSize: Int, ec: ExecutionContext)(
      implicit F: Effect[F]): F[Queue[F, A]] = {
    F.delay {
      val internal = new DBQ[A](qSize)

      new Queue[F, A] {
        def enqueue1(a: A): F[Unit] = F.delay(internal.put(a))

        def offer1(a: A): F[Boolean] = F.delay(internal.offer(a))

        def dequeue1: F[A] = F.delay(internal.take())

        def dequeueBatch1(batchSize: Int): F[Chunk[A]] = F.delay {
          val listBuffer = new ListBuffer[A]
          val j = listBuffer.asJava
          internal.drainTo(j, batchSize)
          Chunk.seq(listBuffer.toList)
        }

        def dequeue: Stream[F, A] = Stream.repeatEval(dequeue1)

        def dequeueBatch: Pipe[F, Int, A] = _.flatMap { i =>
          Stream.eval(dequeueBatch1(i)).flatMap(Stream.chunk(_))
        }

        def peek1: F[A] = F.delay {
          var a: A = null.asInstanceOf[A]
          while (a == null) a = internal.peek()
          a
        }

        def size: F[Int] = F.delay(internal.size())

        def upperBound: Option[Int] = Some(qSize)
      }
    }
  }

}
