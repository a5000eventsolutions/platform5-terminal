package sevts.terminal.platform5

import akka.actor._
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import sevts.remote.protocol.Protocol._
import sevts.server.remote.{Message, Reaction, TerminalMessage}
import sevts.server.remote.TerminalMessage.TerminalPushRequest
import sevts.terminal.Injector
import sevts.terminal.actors.readers.{ReadersActor, Rfid9809ReaderActor}
import sevts.terminal.actors.scanners.ScannersActor
import sevts.terminal.config.Settings
import sevts.terminal.platform5.RemoteScannersActor._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal
import akka.pattern._
import sevts.server.domain.{Id, Terminal}
import sevts.server.protocol.TerminalEvent.OpenFormData
import sevts.server.remote.Message.ScannerMessage
import akka.pattern._

object RemoteScannersActor {

//  def props(injector: Injector) = {
//    Props(classOf[RemoteScannersActor], injector)
//  }
//
//  private case object Warmup
//  private case object Reconnect
//  private case object ActivityCheck
//
//  sealed trait State
//  private object State {
//    case object Identifying extends State
//    case object Registering extends State
//    case object Working extends State
//  }
//
//  sealed trait Data
//  private object Data {
//    case object Empty extends Data
//    case class RemoteActor(actor: ActorRef, lastActivity: Long) extends Data
//    case class Working(id: Id[Terminal], actor: ActorRef, lastActivity: Long) extends Data
//  }
}

class RemoteScannersActor(injector: Injector){

//  import RemoteScannersActor._
//
//  implicit val timeout = Timeout(1 second)
//  implicit val ec = context.dispatcher
//
//  val settings = injector.settings
//
//  def sendIdentifyRequest() = context.actorSelection(path) ! Identify(path)
//
//  def startHeartbeat() = {
//    context.system.scheduler.schedule(5 seconds,
//      5 seconds,
//      self,
//      ActivityCheck)
//  }
//
//  override def preStart() = {
//    if(settings.accessControlEnabled && settings.remoteEnabled) {
//      injector.readersActor ! ReadersActor.Request.RegisterListener(self)
//      context.setReceiveTimeout(3 seconds)
//      logger.info("Starting remote access control")
//      startHeartbeat()
//    } else {
//      logger.info("Access control disabled")
//    }
//  }
//
//  startWith(State.Identifying, Data.Empty)
//
//  when(State.Identifying) {
//    case Event(ActorIdentity(`path`, Some(actor)), _) ⇒
//      logger.info("Remote access served identified")
//      context.watch(actor)
//      context.setReceiveTimeout(Duration.Undefined)
//      self ! Warmup
//      goto(State.Registering) using Data.RemoteActor(actor, System.currentTimeMillis())
//
//    case Event(ActorIdentity(`path`, None), _) ⇒
//      logger.error(s"Remote actor not available: $path")
//      context.system.scheduler.scheduleOnce(15 seconds, self, Reconnect)
//      stay()
//
//    case Event(Reconnect, _) ⇒
//      sendIdentifyRequest()
//      stay()
//
//    case Event(ReceiveTimeout, _) ⇒
//      sendIdentifyRequest()
//      stay()
//
//    case Event(Terminated(actor), _) ⇒
//      logger.error("Access control server connection lost!!!")
//      logger.error("Trying reconnect")
//      sendIdentifyRequest()
//      goto(State.Identifying) using Data.Empty
//
//    case Event(ActivityCheck, _) ⇒
//      logger.error("Inactive timeout! Trying reconnect..")
//      sendIdentifyRequest()
//      stay()
//  }
//
//  when(State.Registering) {
//    case Event(ActorIdentity(`path`, Some(actor)), _) ⇒
//      logger.error("WTF?")
//      context.watch(actor)
//      context.setReceiveTimeout(Duration.Undefined)
//      self ! Warmup
//      stay()
//
//    case Event(Warmup, Data.RemoteActor(actor, _)) ⇒
//      val terminalName = settings.autoLoginConfig.terminal
//      logger.info("Register access control terminal")
//      actor ! RegisterTerminal(terminalName)
//      stay()
//
//    case Event(TerminalRegistered(id), Data.RemoteActor(a, l)) ⇒
//      logger.info("Terminal registered on access control server")
//      goto(State.Working) using Data.Working(id, a, l)
//
//    case Event(RegisterError, _) ⇒
//      logger.error("Terminal register error on access control server")
//      stay()
//
//    case Event(Reconnect, _) ⇒
//      sendIdentifyRequest()
//      goto(State.Identifying) using Data.Empty
//
//    case Event(Ping, data: Data.RemoteActor) ⇒
//      sender() ! Pong
//      stay() using data.copy(lastActivity = System.currentTimeMillis())
//  }
//
//  when(State.Working) {
//
//    case Event(ReadersActor.DeviceEvent.DataReceived(deviceName, data), workData: Data.Working) ⇒
//      injector.scannersActor ? ScannersActor.Request.DataReceived(deviceName, data) map {
//        case ScannersActor.Response.DataProcessed(msg) ⇒
//          convertEvent(workData.id, msg) foreach { event ⇒
//            workData.actor ! event
//          }
//      } recover {
//        case NonFatal(e) ⇒
//          logger.error("Scanned data processing failed", e)
//      }
//      stay()
//
//    case Event(ActivityCheck, Data.RemoteActor(_, lastActivity)) ⇒
//      if(System.currentTimeMillis() - lastActivity > 10000) {
//        logger.error("Inactive timeout! Trying reconnect..")
//        sendIdentifyRequest()
//        goto(State.Identifying) using Data.Empty
//      } else {
//        stay()
//      }
//
//    case Event(Terminated(actor), _) ⇒
//      logger.error("Access control server connection lost!!!")
//      logger.error("Trying reconnect")
//      sendIdentifyRequest()
//      goto(State.Identifying) using Data.Empty
//
//    case Event(Ping, data: Data.Working) ⇒
//      sender() ! Pong
//      stay() using data.copy(lastActivity = System.currentTimeMillis())
//
//    case Event(Reconnect, _) ⇒
//      sendIdentifyRequest()
//      goto(State.Identifying) using Data.Empty
//
//    case unknown ⇒
//      //logger.info(s"Unknown message ${unknown.toString}")
//      stay()
//  }


}
