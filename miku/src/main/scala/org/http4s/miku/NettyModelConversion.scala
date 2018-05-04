package org.http4s.miku

import java.net.InetSocketAddress

import cats.effect.Effect
import cats.syntax.all._
import com.typesafe.netty.http.{DefaultStreamedHttpResponse, StreamedHttpRequest}
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.handler.codec.http._
import io.netty.handler.ssl.SslHandler
import fs2._
import fs2.interop.reactivestreams._
import org.http4s.Request.Connection
import org.http4s.headers.{Date, `Content-Length`, Connection => HConnection}
import org.http4s.util.execution.trampoline
import org.http4s.{HttpVersion => HV, _}
import org.log4s.getLogger

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext

/** Helpers for converting http4s request/response
  * objects to and from the netty model
  *
  * Adapted from NettyModelConversion.scala
  * in
  * https://github.com/playframework/playframework/blob/master/framework/src/play-netty-server
  *
  */
object NettyModelConversion {

  private val logger = getLogger

  /** Turn a netty http request into an http4s request
    *
    * @param channel the netty channel
    * @param request the netty http request impl
    * @return Http4s request
    */
  def fromNettyRequest[F[_]](channel: Channel, request: HttpRequest)(
      implicit F: Effect[F]
  ): F[Request[F]] = {
    val connection = createRemoteConnection(channel)
    if (request.decoderResult().isFailure)
      F.raiseError(ParseFailure("Malformed request", "Netty codec parsing unsuccessful"))
    else {
      val requestBody           = convertRequestBody(request)
      val uri: ParseResult[Uri] = Uri.fromString(request.uri())
      val headerBuf             = new ListBuffer[Header]
      request.headers().entries().forEach { entry =>
        headerBuf += Header(entry.getKey, entry.getValue)
      }
      val method: ParseResult[Method] =
        Method.fromString(request.method().name())
      val version: HV = {
        if (request.protocolVersion() == HttpVersion.HTTP_1_1)
          HV.`HTTP/1.1`
        else
          HV.`HTTP/1.0`
      }
      uri.flatMap { u =>
        method.map { m =>
          Request[F](
            m,
            u,
            version,
            Headers(headerBuf.toList),
            requestBody,
            AttributeMap(AttributeEntry(Request.Keys.ConnectionInfo, connection))
          )
        }
      } match { //Micro-optimization: No fold call
        case Right(http4sRequest) => F.pure(http4sRequest)
        case Left(err)            => F.raiseError(err)
      }
    }
  }

  /** Capture a request's connection info from its channel and headers. */
  private def createRemoteConnection(channel: Channel): Connection =
    Connection(
      channel.localAddress().asInstanceOf[InetSocketAddress],
      channel.remoteAddress().asInstanceOf[InetSocketAddress],
      channel.pipeline().get(classOf[SslHandler]) != null
    )

  /** Create the source for the request body
    * Todo: Turn off scalastyle due to non-exhaustive match
    */
  private def convertRequestBody[F[_]](request: HttpRequest)(
      implicit F: Effect[F]
  ): Stream[F, Byte] =
    request match {
      case full: FullHttpRequest =>
        val content = full.content()
        val buffers = content.nioBuffers()
        if (buffers.isEmpty)
          Stream.empty.covary[F]
        else {
          val content = full.content()
          val arr     = new Array[Byte](content.readableBytes())
          content.readBytes(arr)
          Stream
            .chunk(Chunk.bytes(arr))
            .covary[F]
            .onFinalize(F.delay(full.release()))
        }
      case streamed: StreamedHttpRequest =>
        Stream.suspend(streamed.toStream[F]()(F, trampoline).flatMap { h =>
          val bytes = new Array[Byte](h.content().readableBytes())
          h.content().readBytes(bytes)
          h.release()
          Stream.chunk(Chunk.bytes(bytes)).covary[F]
        })
    }

  /** Create a Netty streamed response. */
  private def responseToPublisher[F[_]](
      response: Response[F]
  )(implicit F: Effect[F], ec: ExecutionContext): StreamUnicastPublisher[F, HttpContent] = {
    def go(s: Stream[F, Byte]): Pull[F, HttpContent, Unit] =
      s.pull.unconsChunk.flatMap {
        case Some((chnk, stream)) =>
          Pull.output1[F, HttpContent](chunkToNetty(chnk)) >> go(stream)
        case None =>
          Pull.eval(response.trailerHeaders).flatMap { h =>
            if (h.isEmpty)
              Pull.done
            else {
              val c = new DefaultLastHttpContent()
              h.foreach(header => c.trailingHeaders().add(header.name.toString(), header.value))
              Pull.output1(c) >> Pull.done
            }
          }
      }
    go(response.body).stream.toUnicastPublisher()
  }

  /** Create a Netty response from the result */
  def toNettyResponse[F[_]](
      http4sResponse: Response[F]
  )(implicit F: Effect[F], ec: ExecutionContext): HttpResponse = {
    val httpVersion: HttpVersion =
      if (http4sResponse.httpVersion == HV.`HTTP/1.1`)
        HttpVersion.HTTP_1_1
      else
        HttpVersion.HTTP_1_0

    if (http4sResponse.status.isEntityAllowed) {
      val publisher = responseToPublisher[F](http4sResponse)
      val response =
        new DefaultStreamedHttpResponse(
          httpVersion,
          HttpResponseStatus.valueOf(http4sResponse.status.code),
          publisher
        )
      http4sResponse.headers.foreach(h => response.headers().add(h.name.value, h.value))
      response
    } else {
      val response = new DefaultFullHttpResponse(
        httpVersion,
        HttpResponseStatus.valueOf(http4sResponse.status.code)
      )
      http4sResponse.headers.foreach(h => response.headers().add(h.name.value, h.value))
      if (HttpUtil.isContentLengthSet(response))
        response.headers().remove(`Content-Length`.name.toString())
      response
    }
  }

  /** Convert a Chunk to a Netty ByteBuf. */
  private def chunkToNetty(bytes: Chunk[Byte]): HttpContent =
    if (bytes.isEmpty)
      CachedEmpty
    else
      bytes match {
        case c: Chunk.Bytes =>
          new DefaultHttpContent(Unpooled.wrappedBuffer(c.values, c.offset, c.length))
        case c: Chunk.ByteBuffer =>
          new DefaultHttpContent(Unpooled.wrappedBuffer(c.buf))
        case _ =>
          new DefaultHttpContent(Unpooled.wrappedBuffer(bytes.toArray))
      }

  private val CachedEmpty: DefaultHttpContent =
    new DefaultHttpContent(Unpooled.EMPTY_BUFFER)

}
