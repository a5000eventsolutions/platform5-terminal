package sevts.terminal.platform5

import akka.actor._
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import sevts.remote.protocol.Protocol._
import sevts.server.domain.{Id, Terminal}
import sevts.terminal.Injector
import sevts.terminal.actors.readers.ReadersActor
import sevts.terminal.networking.websocket.WsClient
import sevts.terminal.platform5.RemoteTransportActor.{Data, State}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal
import akka.pattern._


object RemoteTransportActor {

  def props(injector: Injector): Props = Props(classOf[RemoteTransportActor], injector)

  private case object Warmup
  private case object Reconnect
  private case object ActivityCheck

  sealed trait State
  private object State {
    case object Idle extends State
    case object Connecting extends State
    case object Registering extends State
    case object Working extends State
  }

  sealed trait Data

  sealed trait ClientRef extends Data{
    val wsClient: ActorRef
  }

  private object Data {
    case object EmptyData extends Data
    case class Reconnect(wsClient: ActorRef) extends Data with ClientRef
    case class ConnectionEstablished(wsClient: ActorRef, lastActivity: Long) extends Data with ClientRef
    case class Working(id: Id[Terminal], wsClient: ActorRef, lastActivity: Long) extends Data with ClientRef
  }
}

class RemoteTransportActor(injector: Injector) extends FSM[State, Data] with LazyLogging {

  import RemoteTransportActor._

  implicit val ec = context.dispatcher
  implicit val timeout = Timeout(20 seconds)

  val printerService = new PrinterService(injector)

  val settings = injector.settings

  def startHeartbeat() = {
    context.system.scheduler.schedule(5 seconds,
      10 seconds,
      self,
      ActivityCheck
    )
  }

  def sendConnectRequest() = {
    this.stateData match {
      case d: ClientRef ⇒
        d.wsClient ! PoisonPill
      case _ ⇒
    }
    context.actorOf(WsClient.props(injector, self))
  }

  override def preStart() = {
    if(settings.remoteEnabled) {
      injector.readersActor ! ReadersActor.Request.RegisterListener(self)
      logger.info("Starting remote access control")
      startHeartbeat()
      self ! "start"
    } else {
      logger.info("Access control disabled")
    }
  }

  startWith(State.Idle, Data.EmptyData)

  when(State.Idle) {
    case _ ⇒
      goto(State.Connecting) using Data.Reconnect(sendConnectRequest())
  }

  when(State.Connecting) {

    case Event(WsClient.Connected, reconnect: Data.Reconnect) ⇒
      logger.info("Remote access served identified")
      context.watch(reconnect.wsClient)
      self ! Warmup
      goto(State.Registering) using Data.ConnectionEstablished(reconnect.wsClient, System.currentTimeMillis())

    case Event(WsClient.Disconnected, reconnect: Data.Reconnect) ⇒
      logger.error(s"Connection failed")
      context.system.scheduler.scheduleOnce(15 seconds, self, Reconnect)
      stay()

    case Event(Reconnect, _) ⇒
      logger.error("ident reconnect")
      stay() using Data.Reconnect(sendConnectRequest())

    case Event(ReceiveTimeout, _) ⇒
      logger.error("ident receive timeout")
      stay() using Data.Reconnect(sendConnectRequest())

    case Event(Terminated(actor), _) ⇒
      logger.error("Access control server connection lost!!!")
      logger.error("Trying reconnect")
      goto(State.Connecting) using Data.Reconnect(sendConnectRequest())

    case Event(ActivityCheck, _) ⇒
      logger.error("Inactive timeout! Trying reconnect..")
      sendConnectRequest()
      stay()
  }

  when(State.Registering) {
    case Event(WsClient.Connected, _) ⇒
      logger.error("WTF?")
      context.setReceiveTimeout(Duration.Undefined)
      self ! Warmup
      stay()

    case Event(Warmup, Data.ConnectionEstablished(actor, _)) ⇒
      val terminalName = settings.autoLoginConfig.terminal
      logger.info("Register access control terminal")
      actor ! RegisterTerminal(terminalName)
      stay()

    case Event(TerminalRegistered(id), Data.ConnectionEstablished(a, l)) ⇒
      logger.info("Terminal registered on access control server")
      goto(State.Working) using Data.Working(id, a, l)

    case Event(AccessDenied, _) ⇒
      logger.error(s"Access denied for terminal with name `${settings.autoLoginConfig.terminal}`")
      logger.error("Stopping terminal process..")
      context.system.terminate()
      stop()

    case Event(RegisterError, _) ⇒
      logger.error("Terminal register error on access control server")
      stay()

    case Event(Reconnect, Data.ConnectionEstablished(a, l)) ⇒
      a ! PoisonPill
      goto(State.Connecting) using Data.Reconnect(sendConnectRequest())

    case Event(Ping, data: Data.ConnectionEstablished) ⇒
      logger.error("reg conn established")
      sender() ! Pong
      stay() using data.copy(lastActivity = System.currentTimeMillis())

    case Event(ActivityCheck, data: Data.ConnectionEstablished) ⇒
      if(System.currentTimeMillis() - data.lastActivity > 10000) {
        logger.error("Inactive timeout! Trying reconnect..")
        data.wsClient ! PoisonPill
        goto(State.Connecting) using Data.Reconnect(sendConnectRequest())
      } else {
        stay()
      }

    case Event(ActivityCheck, _) ⇒
      goto(State.Connecting) using Data.Reconnect(sendConnectRequest())
  }

  when(State.Working) {

    case Event(p@RemotePrintFile(_, printer, badge, data), workData: Data.Working) ⇒
      logger.info("Print file command received")
      printerService.print(p) pipeTo sender()
      stay() using workData.copy(lastActivity = System.currentTimeMillis())

    case Event(dr: ReadersActor.DeviceEvent.DataReceived, workData: Data.Working) ⇒
      ScannersService.dataReceived(injector, workData.id, dr) map { resultOpt ⇒
        resultOpt.foreach { result ⇒
          workData.wsClient ! result
        }
      } recover {
        case NonFatal(e) ⇒
          logger.error("Scanned data processing failed", e)
      }
      stay()

    case Event(ActivityCheck, Data.Working(_, actor, lastActivity)) ⇒
      if(System.currentTimeMillis() - lastActivity > 10000) {
        logger.error("Inactive timeout! Trying reconnect..")
        actor ! PoisonPill
        goto(State.Connecting) using Data.Reconnect(sendConnectRequest())
      } else {
        stay()
      }

    case Event(Terminated(actor), _) ⇒
      logger.error("Access control server connection lost!!!")
      logger.error("Trying reconnect")
      goto(State.Connecting) using Data.Reconnect(sendConnectRequest())

    case Event(Ping, data: Data.Working) ⇒
      sender() ! Pong
      stay() using data.copy(lastActivity = System.currentTimeMillis())

    case Event(Reconnect,  data: Data.Working) ⇒
      logger.error("work reconnect")
      data.wsClient ! PoisonPill
      goto(State.Connecting) using Data.Reconnect(sendConnectRequest())

    case unknown ⇒
      logger.info(s"Unknown message ${unknown.toString}")
      stay()
  }


}