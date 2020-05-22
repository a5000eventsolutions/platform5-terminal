package sevts.terminal.transport

import akka.actor._
import akka.pattern._
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import sevts.server.domain.{Id, Terminal}
import sevts.terminal.Injector
import sevts.terminal.modules.readers.ReadersActor
import sevts.terminal.networking.websocket.WsClient
import sevts.terminal.service.{PrinterService, ScannersService}
import sevts.terminal.transport.RemoteTransportActor.{Data, State}
import scala.concurrent.duration._
import scala.language.postfixOps


object RemoteTransportActor {

  def props(injector: Injector): Props = Props(classOf[RemoteTransportActor], injector)

  private[transport] case object Warmup
  private[transport] case object ActivityCheck

  sealed trait State
  private[transport] object State {
    case object Idle extends State
    case object Connecting extends State
    case object Registering extends State
    case object Working extends State
  }

  sealed trait Data

  sealed trait ClientRef extends Data{
    val wsClient: ActorRef
  }

  private[transport] object Data {
    case object EmptyData extends Data
    case class Reconnect(wsClient: ActorRef, lastActivity: Long) extends Data with ClientRef
    case class ConnectionEstablished(wsClient: ActorRef, lastActivity: Long) extends Data with ClientRef
    //uid is unique id (access token) on the server
    case class Working(id: Id[Terminal], uid: String, wsClient: ActorRef, lastActivity: Long) extends Data with ClientRef
  }
}

class RemoteTransportActor(val injector: Injector) extends FSM[State, Data]
  with LazyLogging
  with ConnectingFunction
  with RegisteringFunction
  with WorkingFunction {

  import RemoteTransportActor._

  implicit val ec = context.dispatcher
  implicit val timeout = Timeout(10 seconds)

  val printerService = new PrinterService(injector)

  val settings = injector.settings

  def startHeartbeat() = {
    context.system.scheduler.scheduleAtFixedRate(10 seconds,
      10 seconds,
      self,
      ActivityCheck
    )

  }

  def sendConnectRequest() = {
    this.stateData match {
      case d: ClientRef =>
        logger.error("Force close ws client")
        d.wsClient ! PoisonPill
      case _ =>
        logger.error("Unknown websocket state")
    }
    val client = context.actorOf(WsClient.props(injector, self))
    client
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
    case _ =>
      goto(State.Connecting) using Data.Reconnect(sendConnectRequest(), System.currentTimeMillis())
  }

  when(State.Connecting) { connectingFunction }

  when(State.Registering) { registeringFunction }

  when(State.Working) { workingFunction }


}
