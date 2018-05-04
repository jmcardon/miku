package org.http4s.miku

import java.io.FileInputStream
import java.net.{InetSocketAddress, SocketAddress}
import java.security.{KeyStore, Security}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

import cats.effect.{Effect, IO}
import com.typesafe.netty.HandlerPublisher
import com.typesafe.netty.http.HttpStreamsServerHandler
import fs2.Pipe
import fs2.interop.reactivestreams._
import io.netty.bootstrap.Bootstrap
import io.netty.channel._
import io.netty.channel.epoll.{EpollEventLoopGroup, EpollServerSocketChannel}
import io.netty.channel.group.DefaultChannelGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.{HttpContentDecompressor, HttpRequestDecoder, HttpResponseEncoder}
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout.IdleStateHandler
import org.http4s.HttpService
import org.http4s.server._
import org.http4s.util.bug
import org.log4s.getLogger

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

sealed trait NettyTransport
case object Jdk    extends NettyTransport
case object Native extends NettyTransport

class MikuBuilder[F[_]](
    httpService: HttpService[F],
    socketAddress: InetSocketAddress,
    idleTimeout: Duration,
    maxInitialLineLength: Int,
    maxHeaderSize: Int,
    maxChunkSize: Int,
    sslBits: Option[MikuSSLConfig],
    serviceErrorHandler: ServiceErrorHandler[F],
    transport: NettyTransport,
    ec: ExecutionContext,
    banner: immutable.Seq[String]
)(implicit F: Effect[F])
    extends ServerBuilder[F]
    with IdleTimeoutSupport[F]
    with SSLKeyStoreSupport[F]
    with SSLContextSupport[F] {
  implicit val e     = ec
  private val logger = getLogger

  protected[this] def newRequestHandler(): ChannelInboundHandler =
    new Http4sNettyHandler[F](httpService) {}

  type Self = MikuBuilder[F]

  protected def copy(
      httpService: HttpService[F] = httpService,
      socketAddress: InetSocketAddress = socketAddress,
      idleTimeout: Duration = Duration.Inf,
      maxInitialLineLength: Int = maxInitialLineLength,
      maxHeaderSize: Int = maxHeaderSize,
      maxChunkSize: Int = maxChunkSize,
      sslBits: Option[MikuSSLConfig] = sslBits,
      serviceErrorHandler: ServiceErrorHandler[F] = serviceErrorHandler,
      ec: ExecutionContext = ec,
      banner: immutable.Seq[String] = banner,
      transport: NettyTransport = transport
  ): MikuBuilder[F] =
    new MikuBuilder[F](
      httpService,
      socketAddress,
      idleTimeout,
      maxInitialLineLength,
      maxHeaderSize,
      maxChunkSize,
      sslBits,
      serviceErrorHandler,
      transport,
      ec,
      banner
    )

  def bindSocketAddress(socketAddress: InetSocketAddress): MikuBuilder[F] =
    copy(socketAddress = socketAddress)

  def withExecutionContext(executionContext: ExecutionContext): MikuBuilder[F] =
    copy(ec = executionContext)

  def withServiceErrorHandler(serviceErrorHandler: ServiceErrorHandler[F]): MikuBuilder[F] =
    copy(serviceErrorHandler = serviceErrorHandler)

  //Todo: Router-hack thingy
  def mountService(service: HttpService[F], prefix: String): MikuBuilder[F] =
    copy(httpService = service)

  def start: F[Server[F]] = F.delay {
    val eventLoop = transport match {
      case Jdk    => new NioEventLoopGroup()
      case Native => new EpollEventLoopGroup()
    }

    val allChannels: DefaultChannelGroup = new DefaultChannelGroup(eventLoop.next())

    val (_, boundAddress) = startNetty(eventLoop, allChannels)
    val server = new Server[F] {
      def shutdown: F[Unit] = F.delay {
        // First, close all opened sockets
        allChannels.close().awaitUninterruptibly()
        // Now shutdown the event loop
        eventLoop.shutdownGracefully()
      }

      def onShutdown(f: => Unit): this.type = {
        Runtime.getRuntime.addShutdownHook(new Thread(() => f))
        this
      }

      def address: InetSocketAddress = boundAddress

      def isSecure: Boolean = sslBits.isDefined
    }
    banner.foreach(logger.info(_))
    logger.info(s"༼ つ ◕_◕ ༽つ Started Miku Server at ${server.baseUri} ༼ つ ◕_◕ ༽つ")
    server
  }

  def withBanner(banner: immutable.Seq[String]): MikuBuilder[F] =
    copy(banner = banner)

  def withIdleTimeout(idleTimeout: Duration): MikuBuilder[F] =
    copy(idleTimeout = idleTimeout)

  def withSSL(
      keyStore: SSLKeyStoreSupport.StoreInfo,
      keyManagerPassword: String,
      protocol: String,
      trustStore: Option[SSLKeyStoreSupport.StoreInfo],
      clientAuth: Boolean
  ): MikuBuilder[F] = {
    val auth = if (clientAuth) ClientAuthRequired else NoClientAuth
    val bits = MikuKeyStoreBits(keyStore, keyManagerPassword, protocol, trustStore, auth)
    copy(sslBits = Some(bits))
  }

  def withSSLContext(sslContext: SSLContext, clientAuth: Boolean): MikuBuilder[F] = {
    val auth = if (clientAuth) ClientAuthRequired else NoClientAuth
    copy(sslBits = Some(MikuSSLContextBits(sslContext, auth)))
  }

  private def getContext(): Option[(SSLContext, ClientAuth)] = sslBits.map {
    case MikuKeyStoreBits(keyStore, keyManagerPassword, protocol, trustStore, clientAuth) =>
      val ksStream = new FileInputStream(keyStore.path)
      val ks       = KeyStore.getInstance("JKS")
      ks.load(ksStream, keyStore.password.toCharArray)
      ksStream.close()

      val tmf = trustStore.map { auth =>
        val ksStream = new FileInputStream(auth.path)

        val ks = KeyStore.getInstance("JKS")
        ks.load(ksStream, auth.password.toCharArray)
        ksStream.close()

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)

        tmf.init(ks)
        tmf.getTrustManagers
      }

      val kmf = KeyManagerFactory.getInstance(
        Option(Security.getProperty("ssl.KeyManagerFactory.algorithm"))
          .getOrElse(KeyManagerFactory.getDefaultAlgorithm)
      )

      kmf.init(ks, keyManagerPassword.toCharArray)

      val context = SSLContext.getInstance(protocol)
      context.init(kmf.getKeyManagers, tmf.orNull, null)

      (context, clientAuth)

    case MikuSSLContextBits(context, clientAuth) =>
      (context, clientAuth)
  }

  private def startNetty(
      eventLoop: MultithreadEventLoopGroup,
      allChannels: DefaultChannelGroup
  ): (Channel, InetSocketAddress) = {

    /**
      * Create a sink for the incoming connection channels.
      */
    def channelPipe: Pipe[F, Channel, Unit] = { s =>
      s.evalMap { connChannel =>
        F.delay {

          // Setup the channel for explicit reads
          connChannel
            .config()
            .setOption(ChannelOption.AUTO_READ, java.lang.Boolean.FALSE)

          val pipeline = connChannel.pipeline()
          getContext() match {
            case Some((ctx, auth)) => //Ignore client authentication for now
              val engine = ctx.createSSLEngine()
              engine.setUseClientMode(false)
              auth match {
                case NoClientAuth => ()
                case ClientAuthRequired =>
                  engine.setNeedClientAuth(true)
                case ClientAuthOptional =>
                  engine.setWantClientAuth(true)
              }
              pipeline.addLast("ssl", new SslHandler(engine))

            case None => //Do nothing
          }

          // Netty HTTP decoders/encoders/etc
          pipeline.addLast("decoder", new HttpRequestDecoder(maxInitialLineLength, maxHeaderSize, maxChunkSize))
          pipeline.addLast("encoder", new HttpResponseEncoder())
          pipeline.addLast("decompressor", new HttpContentDecompressor())

          idleTimeout match {
            case Duration.Inf => //Do nothing
            case Duration(timeout, timeUnit) =>
              pipeline.addLast("idle-handler", new IdleStateHandler(0, 0, timeout, timeUnit))

          }

          val requestHandler = newRequestHandler()

          // Use the streams handler to close off the connection.
          pipeline.addLast("http-handler", new HttpStreamsServerHandler(Seq[ChannelHandler](requestHandler).asJava))

          pipeline.addLast("request-handler", requestHandler)

          // And finally, register the channel with the event loop
          val childChannelEventLoop = eventLoop.next()
          childChannelEventLoop.register(connChannel)
          allChannels.add(connChannel)
          ()
        }
      }
    }

    def bind(
        address: InetSocketAddress
    ): (Channel, fs2.Stream[F, Channel]) = {
      val serverChannelEventLoop = eventLoop.next

      // Watches for channel events, and pushes them through a reactive streams publisher.
      val channelPublisher =
        new HandlerPublisher(serverChannelEventLoop, classOf[Channel])
      val addresss = new InetSocketAddress("127.0.0.1", 8080)

      val bootstrap = transport match {
        case Jdk =>
          new Bootstrap()
            .channel(classOf[NioServerSocketChannel])
            .group(serverChannelEventLoop)
            .option(ChannelOption.AUTO_READ, java.lang.Boolean.FALSE) // publisher does ctx.read()
            .handler(channelPublisher)
            .localAddress(addresss)
        case Native =>
          new Bootstrap()
            .channel(classOf[EpollServerSocketChannel])
            .group(serverChannelEventLoop)
            .option(ChannelOption.AUTO_READ, java.lang.Boolean.FALSE) // publisher does ctx.read()
            .handler(channelPublisher)
            .localAddress(addresss)
      }

      val channel = bootstrap.bind.await().channel()
      allChannels.add(channel)

      (channel, channelPublisher.toStream[F]())
    }

    val (serverChannel, channelSource) = bind(socketAddress)
    F.runAsync(
        channelSource
          .through(channelPipe)
          .compile
          .drain
      )(_.fold(IO.raiseError, _ => IO.unit)) //This shouldn't ever throw an error in the first place.
      .unsafeRunSync()
    val boundAddress = serverChannel.localAddress().asInstanceOf[InetSocketAddress]
    if (boundAddress == null) {
      val e = bug("no bound address")
      logger.error(e)("Error in server initialization")
      throw e
    }
    (serverChannel, boundAddress)
  }

}

object MikuBuilder {
  def apply[F[_]: Effect](httpService: HttpService[F]) =
    new MikuBuilder[F](
      httpService,
      socketAddress = ServerBuilder.DefaultSocketAddress,
      idleTimeout = IdleTimeoutSupport.DefaultIdleTimeout,
      maxInitialLineLength = 4096,
      maxHeaderSize = 8192,
      maxChunkSize = 8192,
      sslBits = None,
      serviceErrorHandler = DefaultServiceErrorHandler[F],
      transport = Native,
      ec = ExecutionContext.global,
      banner = MikuBanner
    )

  val MikuBanner =
    """　　　　　　　 ┌--,--‐'￣￣￣ー―-､／＼
      |　　　　　　,イ |／: : : : : : :〈〈二ゝ＼
      |　　　　　/: /: : : : : : : : : ＼|::＼
      |　　　　/: :イ: : ∧: : :∧: : : : :|: :ﾍ
      |　　　 /: : |: :/_ \::/-＼: : : :|: : :ﾍ
      |　　　/: : :|: /　　 \/　　＼: :__|: : : :ﾍ
      |　　 .ｌ: : |:/ o　　　　o　　|::/:::|: : :ﾍ
      |　　 |: : : \/\  |￣￣￣| 　.|:/ー|: : : : |
      |　　 |: : : |ヽ＼.|＿＿＿|＿/|/__| .|: : : |
      |　　|: : : :|　＾_/|_/\_/\　　　　|: : : : |
      |　　|: : : :|　　( | TT ￣|ヽ　　 .|: : : :|
      |　　|: : : :|　　ﾄ-| ||　 | ﾍ　　 |: : : : |
      |　　.|: : : | 　 L/  ||  |  ﾍ　 .|: : : : |
      |　　.ﾍ: : : |　　 |＿＿,､__ﾍ--´　.|: : : : |
      |　　　ﾍ: : :|　  く////\\\\>ヽJ　 |.: : ::/
      |　　　　ﾍ: :|　　　.ﾄ-| .ﾄ-|　　　　|／￣＼:／
      |　　　　　＼／　　　 |_|　|_|　　　　　　　|／
      |  _   _   _        _ _
      | | |_| |_| |_ _ __| | | ___
      | | ' \  _|  _| '_ \_  _(_-<
      | |_||_\__|\__| .__/ |_|/__/
      |             |_|
      |             """.stripMargin.split("\n").toList
}
