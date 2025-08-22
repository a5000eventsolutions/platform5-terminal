package sevts.terminal.networking.websocket

import akka.actor._
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws._
import akka.pattern._
import akka.stream._
import akka.stream.scaladsl.GraphDSL.Builder
import akka.stream.scaladsl._
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.LazyLogging
import ru.CryptoPro.JCP.JCP
import sevts.remote.protocol.Protocol
import sevts.server.domain.FailureType
import sevts.server.utils.AkkaOps
import sevts.terminal.Injector
import sevts.terminal.networking.websocket.WsClient._

import java.io.FileInputStream
import java.security.{KeyStore, Security}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal


object WsClient {

  case class InMessage(bytes: ByteString)

  case class SendMessage(bytes: ByteString)

  case class WSException(e: Throwable)

  case object Connected

  case object Disconnected

  def props(injector: Injector, parent: ActorRef): Props =
    Props(classOf[WsClient], injector, parent)
}

class WsClient(injector: Injector, parent: ActorRef) extends Actor with LazyLogging {

  implicit val system = context.system
  implicit val ec = context.dispatcher

  // Create GOST SSL context
  private def createGostSslContext(): SSLContext = {
    System.setProperty("com.sun.security.enableCRLDP", "true")
    System.setProperty("com.ibm.security.enableCRLDP", "true")


    Security.addProvider(new JCP())
    Security.addProvider(new ru.CryptoPro.ssl.Provider())
    Security.addProvider(new ru.CryptoPro.Crypto.CryptoProvider())
    Security.addProvider(new ru.CryptoPro.reprov.RevCheck())

    def getGostKeyManagers() = {
      val factory = KeyManagerFactory.getInstance("GostX509")
      val pfxStore = KeyStore.getInstance(JCP.HD_STORE_NAME)
      pfxStore.load(null, null)
      val keyPassword = injector.settings.ssl.keystorePassword
      factory.init(pfxStore, keyPassword.toCharArray)
      factory.getKeyManagers
    }

    def getGostTrustManager() = {
      val trustStore = KeyStore.getInstance(JCP.CERT_STORE_NAME, "JCP")
      val tsPath = injector.settings.ssl.truststorePath
      val tsPassword = injector.settings.ssl.truststorePassword
      if (tsPath.trim.isEmpty) throw new IllegalStateException("platform5.server.remote.ssl.truststorePath is not configured")
      if (tsPassword.isEmpty) throw new IllegalStateException("platform5.server.remote.ssl.truststorePassword is not configured")
      trustStore.load(new FileInputStream(tsPath), tsPassword.toCharArray)
      val factory = TrustManagerFactory.getInstance("GostX509")
      factory.init(trustStore)
      factory.getTrustManagers
    }

    val sslCtx = SSLContext.getInstance("GostTLSv1.2", "JTLS"); // Защищенный контекст
    sslCtx.init(getGostKeyManagers(), getGostTrustManager(), null)


    logger.info(s"Supported GOST cipher suites: ${sslCtx.getSupportedSSLParameters.getCipherSuites.mkString(", ")}")
    sslCtx
  }


  def peekMatValue[T, M](src: Source[T, M]): (Source[T, M], Future[M]) = {
    val p = Promise[M]
    val s = src.mapMaterializedValue { m =>
      p.trySuccess(m)
      m
    }
    (s, p.future)
  }

  val (queueSource, futureQueue) = peekMatValue(Source.queue[BinaryMessage](10, OverflowStrategy.fail))
  futureQueue.map { r => self ! r }

  val url = s"${injector.settings.serverHost}/terminalws"

  // Create GOST SSL context if needed
  val sslContextOpt = if (injector.settings.ssl.useGost) {
    try {
      logger.info("Creating GOST SSL context for WebSocket connection")
      Some(createGostSslContext())
    } catch {
      case e: Exception =>
        logger.error(s"Error creating GOST SSL context: ${e.getMessage}", e)
        None
    }
  } else {
    None
  }

  val clientFuture = new AkkaWsClient(injector, url, self, queueSource.asInstanceOf[Source[BinaryMessage, NotUsed]], sslContextOpt).connect()
  clientFuture.map {
    case Done => parent ! Connected
    case e =>
      parent ! Disconnected
      self ! PoisonPill
  }

  val queue = Await.result(futureQueue, 2 seconds)

  override def postStop() = {
    logger.info(s"Websocket client closed")
  }

  def receive = {
    case _ => context.become(working)
  }

  def working: Receive = {

    case msg: Protocol =>
      val serialized = injector.serialization.serialize(msg)
      val binary = serialized.map { s =>
        BinaryMessage.Streamed(Source.single(ByteString(s)))
      }.recover {
        case NonFatal(e) =>
          logger.info(e.getMessage, e)
          throw e
      }.getOrElse(throw FailureType.Unknown)
      queue.offer(binary)

    case SendMessage(bytes) =>
      queue.offer(BinaryMessage.Streamed(Source.single(bytes)))

    case bm: BinaryMessage =>
      val future = bm.dataStream.runFold(ByteString())(_ ++ _)
      val deserialized = future.map { bytes =>
        injector.serialization.deserialize(bytes.toArray, classOf[Protocol]).get
      }.recover {
        case NonFatal(e) =>
          logger.error(e.getMessage, e)
          FailureType.Exception(e.getMessage)
      }
      deserialized pipeTo parent

    case TextMessage.Strict(text) =>
      logger.info(s"Response <- $text")
      parent ! text

    case f: Status.Failure =>
      logger.error(s"Websocket failure. Cause: ${f.cause}")
      self ! PoisonPill
      parent ! WSException(FailureType.Exception(f.cause.getMessage))

    case unknown =>
      logger.error(s"Unknown message ${unknown.toString}")
      parent ! WSException(FailureType.Exception(unknown.toString))
  }
}

class AkkaWsClient(injector: Injector,
                   val webSocketUrl: String,
                   parent: ActorRef,
                   source: Source[BinaryMessage, NotUsed],
                   sslContextOpt: Option[SSLContext] = None)
  extends LazyLogging with AkkaOps {

  val handlerFlow = Flow.fromSinkAndSource(Sink.actorRef[Message](parent, "connection closed"), source)

  implicit val system = injector.system
  implicit val ec = system.dispatcher

  private lazy val clientFlow: Flow[Message, Message, Future[WebSocketUpgradeResponse]] = {
    sslContextOpt match {
      case Some(sslContext) if webSocketUrl.startsWith("wss://") || injector.settings.ssl.useGost =>
        // Use GOST SSL context for WebSocket connection
        val httpsContext = ConnectionContext.httpsClient(sslContext)
        logger.info("Using GOST SSL context for WebSocket connection")
        Http().webSocketClientFlow(WebSocketRequest(webSocketUrl), httpsContext)
      case _ =>
        // Use default SSL context
        logger.info("Using regular connection for WebSocket")
        Http().webSocketClientFlow(WebSocketRequest(webSocketUrl))
    }
  }

  private lazy val webSocketUpgrade = RunnableGraph.fromGraph[(Future[WebSocketUpgradeResponse])](
    GraphDSL.create[ClosedShape, Future[WebSocketUpgradeResponse]](clientFlow) { implicit builder: Builder[Future[WebSocketUpgradeResponse]] =>
      wsFlow =>
        import GraphDSL.Implicits._

        val clientLoop = builder.add(handlerFlow)
        wsFlow.out ~> clientLoop.in
        wsFlow.in <~ clientLoop.out

        ClosedShape
    }).run()


  def connect(): Future[Done] = {
    logger.info(s"Attempting to connect to $webSocketUrl")
    webSocketUpgrade.flatMap {
      upgrade =>
        upgrade.response.status match {
          case StatusCodes.SwitchingProtocols =>
            logger.info(s"Client connected to $webSocketUrl")
            Future.successful(Done)
          case _ =>
            logger.error(s"Connection failed: ${upgrade.response.status}")
            // Remove the UpgradeFailureReason part if not available
            Future.failed(FailureType.Exception(s"Connection failed: ${upgrade.response.status}"))
        }
    }.recoverWith {
      case ex: javax.net.ssl.SSLException =>
        logger.error(s"SSL handshake failed: ${ex.getMessage}")
        Future.failed(FailureType.Exception(s"SSL handshake failed: ${ex.getMessage}"))
      case ex =>
        logger.error(s"Connection failed: ${ex.getMessage}")
        Future.failed(FailureType.Exception(s"Connection failed: ${ex.getMessage}"))
    }
  }
}
