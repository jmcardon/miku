package org.http4s.miku

import java.net.{InetAddress, InetSocketAddress, URI}
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.SSLPeerUnverifiedException

import cats.effect.{Effect, Sync}
import cats.syntax.all._
import org.http4s.{
  AttributeEntry,
  AttributeMap,
  Header,
  Headers,
  HttpDate,
  Method,
  ParseFailure,
  ParseResult,
  Request,
  Response,
  Status,
  Uri,
  HttpVersion => HV
}
import org.http4s.headers.{Date, Server, `Content-Length`, `Content-Type`, Connection => HConnection}

import scala.collection.mutable.ListBuffer
import com.typesafe.netty.http.{DefaultStreamedHttpResponse, StreamedHttpRequest}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.Channel
import io.netty.handler.codec.http._
import io.netty.handler.ssl.SslHandler
import io.netty.util.ReferenceCountUtil
import fs2._
import fs2.interop.reactivestreams._
import org.http4s.Request.Connection

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import org.log4s.getLogger
import org.http4s.util.execution.trampoline

object NettyModelConversion {

  private val logger = getLogger

  def fromNettyRequest[F[_]](channel: Channel, httpRequest: HttpRequest)(
      implicit F: Effect[F]
  ): F[Request[F]] = {
    val connection  = createRemoteConnection(channel)
    val requestBody = convertRequestBody(httpRequest)
    F.fromEither(parseFields(httpRequest).map {
      case (uri, hv, method, headers) =>
        Request[F](
          method,
          uri,
          hv,
          Headers(headers),
          requestBody,
          AttributeMap(AttributeEntry(Request.Keys.ConnectionInfo, connection))
        )
    })
  }

  /**
    * Convert a Netty request to a Play RequestHeader.
    *
    * Will return a failure if there's a protocol error or some other error in the header.
    */
  private def parseFields(request: HttpRequest): Either[ParseFailure, (Uri, HV, Method, List[Header])] =
    if (request.decoderResult().isFailure)
      ParseResult.fail("Malformed request", "Netty codec parsing unsuccessful")
    else {
      val uri: ParseResult[Uri] = Uri.fromString(request.uri())
      val headers               = request.headers()
      val headerBuf             = new ListBuffer[Header]
      headers.entries().forEach { entry =>
        headerBuf += Header(entry.getKey, entry.getValue)
      }
      val method: ParseResult[Method] =
        Method.fromString(request.method().name())
      val version: ParseResult[HV] = {
        if (request.protocolVersion() == HttpVersion.HTTP_1_1)
          ParseResult.success(HV.`HTTP/1.1`)
        else if (request.protocolVersion() == HttpVersion.HTTP_1_0)
          ParseResult.success(HV.`HTTP/1.0`)
        else
          ParseResult.fail("Invalid Http Version", "Incorrect http header matching")
      }
      for {
        u <- uri
        v <- version
        m <- method
      } yield (u, v, m, headerBuf.toList)
    }

  /** Capture a request's connection info from its channel and headers. */
  private def createRemoteConnection(channel: Channel): Connection =
    Connection(
      channel.localAddress().asInstanceOf[InetSocketAddress],
      channel.remoteAddress().asInstanceOf[InetSocketAddress],
      channel.pipeline().get(classOf[SslHandler]) != null
    )

  /** Create the source for the request body */
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

  /** Create a Netty response from the result */
  def toNettyResponse[F[_]](result: Response[F])(implicit F: Effect[F], ec: ExecutionContext): F[HttpResponse] =
    toNettyResponse_[F](result)
      .handleError { e =>
        logger.error(e)("unhandled error")
        val response =
          new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.INTERNAL_SERVER_ERROR,
            Unpooled.EMPTY_BUFFER
          )
        HttpUtil.setContentLength(response, 0)
        response.headers().add(HConnection.name, "close")
        response
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

  /** Create a Netty chunked response. */
  private def toNettyResponse_[F[_]](
      http4sResponse: Response[F]
  )(implicit F: Effect[F], ec: ExecutionContext): F[HttpResponse] =
    if (http4sResponse.status.isEntityAllowed) {
      val publisher = responseToPublisher[F](http4sResponse)
      val response =
        new DefaultStreamedHttpResponse(
          HttpVersion.HTTP_1_1,
          HttpResponseStatus.valueOf(http4sResponse.status.code),
          publisher
        )
      http4sResponse.headers.foreach(h => response.headers().add(h.name.value, h.value))
      //Todo: LOL Mutability. Do I want this?
      if (!response.headers().contains(Date.name))
        response.headers().add(Date.name, dateHeader)
      F.pure(response)
    } else {
      val response = new DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1,
        HttpResponseStatus.valueOf(http4sResponse.status.code)
      )
      http4sResponse.headers.foreach(h => response.headers().add(h.name.value, h.value))
      http4sResponse.trailerHeaders.flatMap { trailers =>
        F.delay {
          trailers.foreach(h => response.trailingHeaders().add(h.name.value, h.value))
          if (HttpUtil.isContentLengthSet(response))
            response.headers().remove(`Content-Length`.name.toString())
          response
        }
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

  // todo: Play hack. Do I need it?
  // cache the date header of the last response so we only need to compute it every second
  private var cachedDateHeader: (Long, String) = (Long.MinValue, null)

  private def dateHeader: String = {
    val currentTimeMillis  = System.currentTimeMillis()
    val currentTimeSeconds = currentTimeMillis / 1000
    cachedDateHeader match {
      case (cachedSeconds, dateHeaderString) if cachedSeconds == currentTimeSeconds =>
        dateHeaderString
      case _ =>
        val dateHeaderString =
          HttpDate.unsafeFromEpochSecond(currentTimeMillis).toString()
        cachedDateHeader = currentTimeSeconds -> dateHeaderString
        dateHeaderString
    }
  }

}
