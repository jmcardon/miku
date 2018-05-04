package org.http4s.miku

import cats.effect.{Async, Effect, IO}
import cats.syntax.all._
import io.netty.channel.ChannelInboundHandlerAdapter
import org.http4s.{HttpService, Request, Response}
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

import cats.syntax.all._
import io.netty.channel._
import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.codec.http._
import io.netty.handler.timeout.IdleStateEvent

import scala.concurrent.{ExecutionContext, Future, Promise}
import org.log4s.getLogger
import org.http4s.util.execution.trampoline

import scala.util.{Failure, Success}

abstract class Http4sNettyHandler[F[_]](service: HttpService[F])(
    implicit F: Effect[F],
    ec: ExecutionContext)
    extends ChannelInboundHandlerAdapter {
  val unwrapped: Request[F] => F[Response[F]] =
    service.mapF(_.getOrElse(Response.notFound[F])).run

  // We keep track of whether there are requests in flight.  If there are, we don't respond to read
  // complete, since back pressure is the responsibility of the streams.
  private val requestsInFlight = new AtomicLong()

  // This is used essentially as a queue, each incoming request attaches callbacks to this
  // and replaces it to ensure that responses are written out in the same order that they came
  // in.
  private var lastResponseSent: Future[Unit] = Future.successful(())

  private val logger = getLogger

  /**
    * Handle the given request.
    */
  def handle(channel: Channel, request: HttpRequest): F[HttpResponse] = {
    logger.trace("Http request received by netty: " + request)
    NettyModelConversion
      .fromNettyRequest[F](channel, request)
      .flatMap(Async.shift(ec) >> unwrapped(_))
      .flatMap(NettyModelConversion.toNettyResponse[F])
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: Object): Unit = {
    logger.trace(s"channelRead: ctx = $ctx, msg = $msg")
    msg match {
      case req: HttpRequest =>
        requestsInFlight.incrementAndGet()
        val p: Promise[HttpResponse] = Promise[HttpResponse]
        F.runAsync(handle(ctx.channel(), req)) {
            case Left(error) =>
              IO {
                logger.error(error)("Exception caught in channelRead future")
                p.complete(Failure(error))
              }

            case Right(httpResponse) =>
             IO {
                p.complete(Success(httpResponse))
              }
          }.unsafeRunSync()
        val futureResponse = p.future

        lastResponseSent = lastResponseSent.flatMap[Unit] { _ =>
          futureResponse
            .map[Unit] { response =>
            if (requestsInFlight.decrementAndGet() == 0) {
              // Since we've now gone down to zero, we need to issue a
              // read, in case we ignored an earlier read complete
              ctx.read()
            }
            ctx.writeAndFlush(response)
          }(trampoline).recover[Unit] {
              case error: Exception =>
                logger.error(error)("Exception caught in channelRead future")
                sendSimpleErrorResponse(ctx,
                                        HttpResponseStatus.SERVICE_UNAVAILABLE)
            }(trampoline)
        }(trampoline)

      case _ =>
        logger.error("Invalid type")
    }
  }

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
    logger.trace(s"channelReadComplete: ctx = $ctx")

    // The normal response to read complete is to issue another read,
    // but we only want to do that if there are no requests in flight,
    // this will effectively limit the number of in flight requests that
    // we'll handle by pushing back on the TCP stream, but it also ensures
    // we don't get in the way of the request body reactive streams,
    // which will be using channel read complete and read to implement
    // their own back pressure
    if (requestsInFlight.get() == 0) {
      ctx.read()
    } else {
      // otherwise forward it, so that any handler publishers downstream
      // can handle it
      ctx.fireChannelReadComplete()
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext,
                               cause: Throwable): Unit = {
    cause match {
      // IO exceptions happen all the time, it usually just means that the client has closed the connection before fully
      // sending/receiving the response.
      case e: IOException =>
        logger.trace(e)("Benign IO exception caught in Netty")
        ctx.channel().close()
      case e: TooLongFrameException =>
        logger.warn(e)("Handling TooLongFrameException")
        sendSimpleErrorResponse(ctx, HttpResponseStatus.REQUEST_URI_TOO_LONG)
      case e: IllegalArgumentException
          if Option(e.getMessage).exists(
            _.contains("Header value contains a prohibited character")) =>
        // https://github.com/netty/netty/blob/netty-3.9.3.Final/src/main/java/org/jboss/netty/handler/codec/http/HttpHeaders.java#L1075-L1080
        logger.debug(e)("Handling Header value error")
        sendSimpleErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST)
      case e =>
        logger.error(e)("Exception caught in Netty")
        ctx.channel().close()
    }
  }

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    // AUTO_READ is off, so need to do the first read explicitly.
    // this method is called when the channel is registered with the event loop,
    // so ctx.read is automatically safe here w/o needing an isRegistered().
    ctx.read()
  }

  override def userEventTriggered(ctx: ChannelHandlerContext,
                                  evt: scala.Any): Unit = {
    evt match {
      case idle: IdleStateEvent if ctx.channel().isOpen =>
        logger.trace(s"Closing connection due to idle timeout")
        ctx.close()
      case _ => super.userEventTriggered(ctx, evt)
    }
  }

  private def sendSimpleErrorResponse(
      ctx: ChannelHandlerContext,
      status: HttpResponseStatus): ChannelFuture = {
    val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status)
    response.headers().set(HttpHeaderNames.CONNECTION, "close")
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0")
    val f = ctx.channel().write(response)
    f.addListener(ChannelFutureListener.CLOSE)
    f
  }
}
