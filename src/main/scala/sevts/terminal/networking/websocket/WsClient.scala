package sevts.terminal.networking.websocket

import akka.{Done, NotUsed}
import akka.actor._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws._
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
import sevts.remote.protocol.Protocol
import sevts.server.domain.FailureType

import scala.language.postfixOps
import scala.util.Try
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

  import sevts.server.protocol._

  implicit val materializer = ActorMaterializer()
  implicit val ec = context.dispatcher

  def peekMatValue[T, M](src: Source[T, M]): (Source[T, M], Future[M]) = {
    val p = Promise[M]
    val s = src.mapMaterializedValue { m =>
      p.trySuccess(m)
      m
    }
    (s, p.future)
  }

  val (queueSource, futureQueue) = peekMatValue(Source.queue[BinaryMessage](10, OverflowStrategy.fail))
  futureQueue.map { r ⇒ self ! r }

  val url = s"ws://${injector.settings.serverHost}:${injector.settings.webSocketPort.toString}/terminalws"
  val clientFuture = new AkkaWsClient(injector, url, self, queueSource.asInstanceOf[Source[BinaryMessage, NotUsed]]).connect()
  clientFuture.map {
    case Done ⇒ parent ! Connected
    case e ⇒
      parent ! Disconnected
      self ! PoisonPill
  }

  val queue = Await.result(futureQueue, 2 seconds)

  override def postStop() = {
    logger.info(s"Websocket client closed")
  }

  def receive = {
    case _ ⇒ context.become(working)
  }

  def working: Receive = {

    case msg: Protocol ⇒
      val serialized = injector.serialization.serialize(msg)
      val binary = serialized.map { s ⇒
        BinaryMessage.Streamed(Source.single(ByteString(s)))
      }.recover {
        case NonFatal(e) ⇒
          logger.info(e.getMessage, e)
          throw e
      }.getOrElse(throw FailureType.Unknown)
      queue.offer(binary)

    case SendMessage(bytes) ⇒
      queue.offer(BinaryMessage.Streamed(Source.single(bytes)))

    case bm: BinaryMessage ⇒
      val future = bm.dataStream.runFold(ByteString())(_ ++ _)
      val deserialized = future map { bytes ⇒
        injector.serialization.deserialize(bytes.toArray, classOf[Protocol]).recover {
          case NonFatal(e) ⇒
            logger.info(e.getMessage)
            throw e
        }.getOrElse(throw FailureType.Unknown)
      }
      deserialized pipeTo parent

    case TextMessage.Strict(text) ⇒
      logger.info(s"Response <- $text")
      parent ! text

    case unknown ⇒
      logger.error(s"Unknown message ${unknown.toString}")
      parent ! WSException(FailureType.Exception(unknown.toString))
  }
}

class AkkaWsClient(injector: Injector, val webSocketUrl: String, parent: ActorRef, source: Source[BinaryMessage, NotUsed]) extends LazyLogging {

  val handlerFlow = Flow.fromSinkAndSource(Sink.actorRef[Message](parent, "connection closed"), source)

  implicit val system = injector.system
  implicit val ec = system.dispatcher
  implicit val materializer = ActorMaterializer()

  private lazy val clientFlow: Flow[Message, Message, Future[WebSocketUpgradeResponse]] =
    Http().webSocketClientFlow(WebSocketRequest(webSocketUrl))

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
    println(s"Attempting to connect to $webSocketUrl")
    webSocketUpgrade.flatMap {
      upgrade ⇒ upgrade.response.status match {
        case StatusCodes.SwitchingProtocols ⇒
          logger.info(s"Client connected to $webSocketUrl")
          Future.successful(Done)
        case _ =>
          logger.error(s"Connection failed: ${upgrade.response.status}")
          Future.failed(FailureType.Exception(s"Connection failed: ${upgrade.response.status}"))
      }
    }
  }
}


