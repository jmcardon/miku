package org.http4s

import java.util.concurrent.atomic.AtomicLong

import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.http._
import org.http4s.util.execution._

import scala.concurrent.Future

class RequestHandler extends ChannelInboundHandlerAdapter {

  // We keep track of whether there are requests in flight.  If there are, we don't respond to read
  // complete, since back pressure is the responsibility of the streams.
  private val requestsInFlight: AtomicLong = new AtomicLong()

  // This is used essentially as a queue, each incoming request attaches callbacks to this
  // and replaces it to ensure that responses are written out in the same order that they came
  // in.
  private var lastResponseSent: Future[Unit] = Future.successful(())


  override def channelReadComplete(ctx: ChannelHandlerContext): Unit =
    super.channelReadComplete(ctx)

  override def exceptionCaught(ctx: ChannelHandlerContext,
                               cause: Throwable): Unit =
    super.exceptionCaught(ctx, cause)

  override def channelWritabilityChanged(ctx: ChannelHandlerContext): Unit =
    super.channelWritabilityChanged(ctx)

  override def channelInactive(ctx: ChannelHandlerContext): Unit =
    super.channelInactive(ctx)

  override def userEventTriggered(ctx: ChannelHandlerContext,
                                  evt: scala.Any): Unit =
    super.userEventTriggered(ctx, evt)

  override def channelActive(ctx: ChannelHandlerContext): Unit =
    super.channelActive(ctx)

  override def channelRead(ctx: ChannelHandlerContext, msg: scala.Any): Unit = {
    val h = msg.asInstanceOf[HttpRequest]


  }
//    super.channelRead(ctx, msg)

  override def channelUnregistered(ctx: ChannelHandlerContext): Unit =
    super.channelUnregistered(ctx)

  override def channelRegistered(ctx: ChannelHandlerContext): Unit =
    super.channelRegistered(ctx)
}
