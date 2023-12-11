package sevts.terminal.actors.readers

import akka.Done
import akka.actor.{Actor, ActorRef, Cancellable, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.stream.RestartSettings
import akka.stream.scaladsl.{Flow, Keep, RestartSource, Sink, Source}
import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.actors.readers.VLAccessReaderActor.TimeoutConnection
import sevts.terminal.actors.readers.vlaccess.VLAccessReader
import sevts.terminal.config.Settings.DeviceConfig

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}


object VLAccessReaderActor {
  def props(listener: ActorRef, config: DeviceConfig): Props = {
    Props(new VLAccessReaderActor(listener, config))
  }

  case object TimeoutConnection
}

class VLAccessReaderActor(listener: ActorRef,
                          device: DeviceConfig)
  extends Actor
    with LazyLogging {

  logger.info("VLAccess actor starting..")

  implicit val system = context.system
  implicit val ec = system.dispatcher

  val url = device.parameters.getString("url")

  var timeoutTimer: Cancellable = null

  def webSocketFlow = Http().webSocketClientFlow(WebSocketRequest(url))

  val incoming: Sink[Message, Future[Done]] =
    Sink.foreach[Message] {
      case message: TextMessage.Strict =>
        timeoutTimer.cancel()
        timeoutTimer = context.system.scheduler.scheduleOnce(10.seconds, self, TimeoutConnection)
        self ! message
      case _ =>
      // ignore other message types
    }

  val restartSettings = RestartSettings(minBackoff = 3.seconds, maxBackoff = 3.seconds, randomFactor = 0.2)
  def outgoing = RestartSource.withBackoff(restartSettings) { () => Source.never[TextMessage] }

  private def startConnection() = {
    logger.info(s"Start connection to ${url}")
    val (upgradeResponse, closed) =
      outgoing
        .viaMat(webSocketFlow)(Keep.right) // keep the materialized Future[WebSocketUpgradeResponse]
        .toMat(incoming)(Keep.both) // also keep the Future[Done]
        .run()

    val connected = upgradeResponse.flatMap { upgrade =>
      if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
        logger.info("Switching protocols")
        timeoutTimer = context.system.scheduler.scheduleOnce(10.seconds, self, TimeoutConnection)
        Future.successful(Done)
      } else {
        logger.error(s"Connection failed: ${upgrade.response.status}")
        timeoutTimer = context.system.scheduler.scheduleOnce(1.seconds, self, TimeoutConnection)
        Future.successful(Done)
      }
    }

    connected.onComplete {
      case Failure(err) =>
        logger.error(s"VC failed ${err.toString}")
        timeoutTimer = context.system.scheduler.scheduleOnce(1.seconds, self, TimeoutConnection)
      case Success(value) =>
        logger.info("Connected successfully")
    }
    closed.foreach { _ =>
      logger.error("VS Access closed")
    }
  }

  override def preStart(): Unit = {
    startConnection()
  }

  override def receive: Receive = {

    case TimeoutConnection =>
      startConnection()

    case TextMessage.Strict(msg) =>
      logger.info(msg)
      VLAccessReader.parseData(msg) match {
        case Left(e) =>
          logger.info("ping")
        case Right(Some(candidate)) =>
          listener ! ReadersActor.DeviceEvent.DataReceived(device.name, candidate.external_id)

        case Right(None) =>
          listener ! ReadersActor.DeviceEvent.DataReceived(device.name, "invalid data")
      }

    case unknown =>
      logger.info(unknown.toString)
  }

}


