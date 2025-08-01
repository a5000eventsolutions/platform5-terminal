package sevts.terminal.networking.websocket

import akka.{Done, NotUsed}
import akka.actor._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.ConnectionContext
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.Injector
import sevts.terminal.networking.websocket.WsClient._

import scala.concurrent._
import scala.concurrent.duration._
import akka.pattern._
import akka.stream.scaladsl.GraphDSL.Builder
import com.typesafe.sslconfig.ssl.certificate2X509Certificate
import sevts.remote.protocol.Protocol
import sevts.server.domain.FailureType
import sevts.server.utils.AkkaOps

import scala.language.postfixOps
import scala.util.{Try, Using}
import scala.util.control.NonFatal
import java.security.{KeyStore, Provider, SecureRandom, Security}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManager, TrustManagerFactory, X509TrustManager}
import java.io.{File, FileInputStream, InputStream}
import java.security.cert.{CertificateFactory, X509Certificate}
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.{CollectionHasAsScala, EnumerationHasAsScala}
import scala.util.{Failure, Success}

import ru.CryptoPro.JCP.ASN.PKIX1Explicit88.Extension;
import ru.CryptoPro.JCP.JCP;
import ru.CryptoPro.JCP.Random.BioRandomConsole;
import ru.CryptoPro.JCPRequest.GostCertificateRequest;


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

  import sevts.server.protocol._

  implicit val system = context.system
  implicit val ec = context.dispatcher

  // Create GOST SSL context
  private def createGostSslContext(): SSLContext = {
    val settings = injector.settings

    try {
      // В начале создания SSLContext
      Security.setProperty("ssl.KeyManagerFactory.algorithm", "GostX509"); // ГОСТ алгоритм
      Security.setProperty("ssl.TrustManagerFactory.algorithm", "GostX509"); // ГОСТ алгоритм
      Security.setProperty("ssl.SocketFactory.provider", "ru.CryptoPro.ssl.SSLSocketFactoryImpl"); // задаем реализацию сокетов с ГОСТ алгоритмами
      Security.setProperty("ssl.ServerSocketFactory.provider", "ru.CryptoPro.ssl.SSLServerSocketFactoryImpl"); // задаем реализацию сокетов сервера с ГОСТ алгоритмами
      System.setProperty("javax.net.ssl.trustStoreType", "CertStore"); // может быть другой тип
      System.setProperty("javax.net.ssl.trustStoreProvider", "JCP"); // может быть другой провайдер
      System.setProperty("javax.net.ssl.keyStoreType", "HDIMAGE"); // может быть другой тип
      System.setProperty("javax.net.ssl.keyStoreProvider", "JCSP"); // может быть другой провайдер
      System.setProperty("ngate_set_jcsp_if_gost", "true");
      System.setProperty("ru.CryptoPro.defaultSSLProv", "JCSP");

      if (Security.getProvider("JCP") == null) {
        Security.addProvider(new JCP())
      }


      logger.info(s"Providers: ${Security.getProviders().map(_.getName).mkString(", ")}")

      val trustStore = KeyStore.getInstance(JCP.HD_STORE_NAME)
      trustStore.load(null, null)

      val certFactory = CertificateFactory.getInstance("X.509")

      def loadCertificate(certPath: String): Unit = {
        val resourceStream = Try(getClass.getClassLoader.getResourceAsStream(certPath))
        resourceStream match {
          case Success(stream) if stream != null =>
            Using.resource(stream) { inputStream =>
              if (certPath.endsWith(".p7b")) {
                logger.info(s"Loading PKCS#7 certificate from resource: $certPath")
                val certs = certFactory.generateCertificates(inputStream)
                certs.forEach { cert =>
                  val alias = s"gost-cert-${cert.getSubjectX500Principal.getName}"
                  trustStore.setCertificateEntry(alias, cert)
                  logger.info(s"Added PKCS#7 certificate: $alias")
                }
              } else {
                logger.info(s"Loading certificate from resource: $certPath")
                val cert = certFactory.generateCertificate(inputStream)
                trustStore.setCertificateEntry(s"gost-cert-${cert.getSubjectX500Principal.getName}", cert)
                logger.info(s"Added certificate from resource: $certPath")
              }
            }
          case _ =>
            logger.warn(s"Certificate resource not found: $certPath")
        }
      }

      List(
        settings.ssl.gostCertPath,
        settings.ssl.gostP7bPath,
        settings.ssl.gostRootCertPath
      ).foreach(loadCertificate)


      val tmf =  TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm()
      )
      tmf.init(trustStore)

      val sslContext =  SSLContext.getInstance("GostTLSv1.2", "JCP")// протокол TLS v.1.2
      sslContext.init(null, tmf.getTrustManagers, new SecureRandom())

      val params = sslContext.getDefaultSSLParameters
      params.setProtocols(Array("GostTLSv1.2", "GostTLSv1.3"))

      // Try more compatible cipher suites
      val cipherSuites = Array(
        // Основные GOST-шифры (из вашего списка)
        "TLS_GOSTR341112_256_WITH_KUZNYECHIK_CTR_OMAC",
      // Кузнечик
      "TLS_GOSTR341112_256_WITH_MAGMA_CTR_OMAC",
      // Магма
      // Стандартные ГОСТ (если поддерживаются)
      "TLS_GOSTR341001_WITH_28147_CNT_MD5",
      // ГОСТ 28147-89
      // Совместимые алгоритмы
      "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
      "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
      // Резервные варианты
      "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384"
      ).filter(sslContext.getSupportedSSLParameters.getCipherSuites.contains)

      logger.info(s"Supported GOST cipher suites: ${cipherSuites.mkString(", ")}")
      if (cipherSuites.isEmpty) {
        sslContext.createSSLEngine().setEnabledCipherSuites(cipherSuites)
        logger.warn("No supported GOST cipher suites found, using default ones")
        params.setCipherSuites(sslContext.getSupportedSSLParameters.getCipherSuites)
      } else {
        params.setCipherSuites(cipherSuites)
      }

      trustStore.aliases().asScala.foreach { alias =>
        val cert = trustStore.getCertificate(alias)
        logger.info(s"Trusted cert: $alias - ${cert.getSubjectX500Principal}")
      }
      sslContext
    } catch {
      case e: Exception =>
        logger.error(s"Error creating GOST SSL context: ${e.getMessage}", e)
        throw e
    }
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

class AkkaWsClient(injector: Injector, val webSocketUrl: String, parent: ActorRef, source: Source[BinaryMessage, NotUsed], sslContextOpt: Option[SSLContext] = None)
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
    GraphDSL.create[ClosedShape, Future[WebSocketUpgradeResponse]] (clientFlow)
      { implicit builder: Builder[Future[WebSocketUpgradeResponse]] => wsFlow =>
        import GraphDSL.Implicits._

        val clientLoop = builder.add(handlerFlow)
        wsFlow.out ~> clientLoop.in
        wsFlow.in <~ clientLoop.out

        ClosedShape
      }).run()


  def connect(): Future[Done] = {
    logger.info(s"Attempting to connect to $webSocketUrl")
    webSocketUpgrade.flatMap {
      upgrade => upgrade.response.status match {
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
