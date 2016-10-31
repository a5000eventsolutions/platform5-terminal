package sevts.terminal.platform5

import akka.actor._
import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.RootActors
import sevts.terminal.actors.readers.{ReadersActor, Rfid9809ReaderActor}
import sevts.terminal.config.Settings
import sevts.terminal.platform5.RemoteAccessControlActor._
import akka.pattern._
import akka.util.Timeout
import sevts.server.protocol.ServerMessage.PushMessageToTerminal
import sevts.server.protocol.{EventType, ServerResponseDetails}
import sevts.server.protocol.printing.Protocol.{TerminalRegistered, _}
import sevts.server.remote.Message.TerminalEPCEvent
import sevts.server.remote.TerminalMessage
import sevts.server.remote.TerminalMessage.TerminalPushMessage
import sevts.terminal.actors.scanners.ScannersActor

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal

object RemoteAccessControlActor {

  def props(settings: Settings, rootActors: RootActors) = {
    Props(classOf[RemoteAccessControlActor], settings, rootActors)
  }

  private case object Warmup
  private case object Reconnect
  private case object ActivityCheck

  sealed trait State
  private object State {
    case object Identifying extends State
    case object Working extends State
  }

  sealed trait Data
  private object Data {
    case object Empty extends Data
    case class RemoteActor(actor: ActorRef, lastActivity: Long) extends Data
  }
}

class RemoteAccessControlActor(settings: Settings, rootActors: RootActors) extends FSM[State, Data] with LazyLogging {

  import RemoteAccessControlActor._

  implicit val timeout = Timeout(1 second)
  implicit val ec = context.dispatcher

  val path = s"${settings.remoteServer.path}/user/terminal-endpoint-actor"

  def sendIdentifyRequest() = context.actorSelection(path) ! Identify(path)

  def startHeartbeat() = {
    context.system.scheduler.schedule(5 seconds,
      5 seconds,
      self,
      ActivityCheck)
  }

  override def preStart() = {
    if(settings.accessControlEnabled && settings.remoteEnabled) {
      rootActors.readersActor ! ReadersActor.Request.RegisterListener(self)
      context.setReceiveTimeout(3 seconds)
      logger.info("Starting remote access control")
      startHeartbeat()
    } else {
      logger.info("Access control disabled")
    }
  }

  startWith(State.Identifying, Data.Empty)

  when(State.Identifying) {
    case Event(ActorIdentity(`path`, Some(actor)), _) ⇒
      logger.info("Remote access served identified")
      context.watch(actor)
      context.setReceiveTimeout(Duration.Undefined)
      self ! Warmup
      goto(State.Working) using Data.RemoteActor(actor, System.currentTimeMillis())

    case Event(ActorIdentity(`path`, None), _) ⇒
      logger.error(s"Remote actor not available: $path")
      context.system.scheduler.scheduleOnce(15 seconds, self, Reconnect)
      stay()

    case Event(Reconnect, _) ⇒
      sendIdentifyRequest()
      stay()

    case Event(ReceiveTimeout, _) ⇒
      sendIdentifyRequest()
      stay()

    case Event(Terminated(actor), _) ⇒
      logger.error("Access control server connection lost!!!")
      logger.error("Trying reconnect")
      sendIdentifyRequest()
      goto(State.Identifying) using Data.Empty

    case Event(ActivityCheck, _) ⇒
      logger.error("Inactive timeout! Trying reconnect..")
      sendIdentifyRequest()
      stay()
  }

  when(State.Working) {
    case Event(ActorIdentity(`path`, Some(actor)), _) ⇒
      logger.error("WTF?")
      context.watch(actor)
      context.setReceiveTimeout(Duration.Undefined)
      self ! Warmup
      stay()

    case Event(Warmup, Data.RemoteActor(actor, _)) ⇒
      val terminalName = settings.autoLoginConfig.terminal
      logger.info("Register access control terminal")
      actor ! RegisterTerminal(terminalName)
      stay()

    case Event(TerminalRegistered(id), _) ⇒
      logger.info("Terminal registered on access control server")
      stay()

    case Event(RegisterError, _) ⇒
      logger.error("Terminal register error on access control server")
      stay()

    case Event(msg: TerminalEPCEvent,  Data.RemoteActor(actor, _)) ⇒
      actor ! TerminalPushMessage(TerminalMessage.DataReceived, msg)
      stay()

    case Event(ReadersActor.DeviceEvent.DataReceived(deviceName, data), Data.RemoteActor(actor, _)) ⇒
      rootActors.scannersActor ? ScannersActor.Request.DataReceived(deviceName, data) map {
        case ScannersActor.Response.DataProcessed(msg) ⇒
          actor ! TerminalPushMessage(TerminalMessage.DataReceived, msg)
      } recover {
        case e: Throwable if NonFatal(e) ⇒
          logger.error("Scanned data processing failed", e)
      }
      stay()

    case Event(ReadersActor.DeviceEvent.EPCReceived(deviceName, data), Data.RemoteActor(actor, _)) ⇒
      rootActors.scannersActor ? ScannersActor.Request.EPCReceived(deviceName, data) map {
        case ScannersActor.Response.DataProcessed(msg) ⇒
          actor ! TerminalPushMessage(TerminalMessage.DataReceived, msg)
      } recover {
        case e: Throwable if NonFatal(e) ⇒
          logger.error("Scanned data processing failed", e)
      }
      stay()

    case Event(PushMessageToTerminal(_, eventType, Right(ServerResponseDetails.Data(value))), Data.RemoteActor(actor, _)) ⇒
      if(eventType == EventType.WriteEPCValue.name) {
        rootActors.readersActor ! Rfid9809ReaderActor.Commands.WriteEpcData(value)
      }
      stay()

    case Event(ActivityCheck, Data.RemoteActor(_, lastActivity)) ⇒
      if(System.currentTimeMillis() - lastActivity > 10000) {
        logger.error("Inactive timeout! Trying reconnect..")
        sendIdentifyRequest()
        goto(State.Identifying) using Data.Empty
      } else {
        stay()
      }


    case Event(Terminated(actor), _) ⇒
      logger.error("Access control server connection lost!!!")
      logger.error("Trying reconnect")
      sendIdentifyRequest()
      goto(State.Identifying) using Data.Empty

    case Event(Ping, data: Data.RemoteActor) ⇒
      sender() ! Pong
      stay() using data.copy(lastActivity = System.currentTimeMillis())

    case Event(Reconnect, _) ⇒
      sendIdentifyRequest()
      goto(State.Identifying) using Data.Empty

    case unknown ⇒
      //logger.info(s"Unknown message ${unknown.toString}")
      stay()
  }
}
