package sevts.terminal.networking

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.util.Timeout
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import sevts.server.remote.Message.AutoLoginMessage
import sevts.server.remote.TerminalMessage.TerminalPushMessage
import sevts.terminal.RootActors
import sevts.terminal.actors.readers.ReadersActor
import sevts.terminal.config.Settings
import sevts.terminal.networking.AppServer.ServerEvent
import sevts.terminal.actors.scanners.ScannersActor
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal

object WebSessionActor {

  def props(settings: Settings, rootActors: RootActors): Props = {
    Props(classOf[WebSessionActor], settings, rootActors)
  }

  sealed trait Request
  object Request {

  }

  sealed trait Response
  object Response {

  }
}

class WebSessionActor(settings: Settings, rootActors: RootActors) extends Actor with LazyLogging {
  implicit val timeout = Timeout(500.millis)
  implicit val ec = context.dispatcher
  implicit val system = context.system

  var backendRef: Option[ActorRef] = None

  override def preStart() = {
    rootActors.readersActor ! ReadersActor.Request.RegisterListener(self)
  }

  override def postStop() = {
    rootActors.readersActor ! ReadersActor.Request.RemoveListener(self)
  }

  override def receive = {
    case ServerEvent.ConnectionOpen(id, backend) ⇒
      backendRef = Some(backend)
      if ( settings.autoLoginConfig.enabled ) {
        backend ! TerminalPushMessage("login", AutoLoginMessage(settings.autoLoginConfig.username,
          settings.autoLoginConfig.password,
          settings.autoLoginConfig.terminal))
      }

    case ServerEvent.ConnectionClosed ⇒
      self ! PoisonPill

    case ReadersActor.DeviceEvent.DataReceived(deviceName, data) ⇒
      rootActors.scannersActor ? ScannersActor.Request.DataReceived(deviceName, data) map {
        case ScannersActor.Response.DataProcessed(msg) ⇒
            backendRef foreach { ref => ref ! TerminalPushMessage("command", msg) }
      } recover {
        case e: Throwable if NonFatal(e) ⇒
          logger.error("Scanned data processing failed", e)
      }

    case ReadersActor.DeviceEvent.EPCReceived(deviceName, data) ⇒
      logger.info("epc rcv")
      rootActors.scannersActor ? ScannersActor.Request.EPCReceived(deviceName, data) map {
        case ScannersActor.Response.DataProcessed(msg) ⇒
          backendRef foreach { ref => ref ! TerminalPushMessage("command", msg) }
      } recover {
        case e: Throwable if NonFatal(e) ⇒
          logger.error("Scanned data processing failed", e)
      }

    case msg ⇒
      logger.info(msg.toString)
  }

}
