package sevts.terminal.platform5

import java.time.Instant
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
import sevts.server.protocol.TerminalEvent
import sevts.terminal.networking.websocket.WsClient.WSException


object RemoteTransportActor {

  def props(injector: Injector): Props = Props(classOf[RemoteTransportActor], injector)

  private case object Warmup
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
    case class Reconnect(wsClient: ActorRef, lastActivity: Long) extends Data with ClientRef
    case class ConnectionEstablished(wsClient: ActorRef, lastActivity: Long) extends Data with ClientRef
    //uid is unique id (access token) on the server
    case class Working(id: Id[Terminal], uid: String, wsClient: ActorRef, lastActivity: Long) extends Data with ClientRef
  }

  private object ExitCode {
    // Connecting
    val RemoteAccessServedIdentified = 1
    // Registering
    val TerminalRegistered = 2
    val RegConnEstablished = 3

    // Connecting
    val CatchException = 101
    val IdentReceiveTimeout = 102
    val ConnectingTerminated = 103
    val ConnectionTimeout = 104
    val ConnectionFailed = 105

    // Registering
    val WTF = 111
    val AccessDenied = 112
    val TerminalRegisterError = 113
    val RegisteringInactiveTimeout = 114
    val RegisteringTerminated = 115
  }
}

class RemoteTransportActor(injector: Injector) extends FSM[State, Data] with LazyLogging {

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

  when(State.Connecting) {

    case Event(WsClient.Connected, reconnect: Data.Reconnect) =>
      logger.info("Remote access served identified")
      //testAuthEnabled(ExitCode.RemoteAccessServedIdentified) // not used yet
      context.watch(reconnect.wsClient)
      self ! Warmup
      goto(State.Registering) using Data.ConnectionEstablished(reconnect.wsClient, System.currentTimeMillis())

    case Event(WsClient.Disconnected, reconnect: Data.Reconnect) =>
      logger.error(s"Connection failed")
      testAuthEnabled(ExitCode.ConnectionFailed)
      stay()

    case Event(ReceiveTimeout, reconnect: Data.Reconnect) =>
      logger.error(s"ident receive timeout ${reconnect.wsClient}")
      testAuthEnabled(ExitCode.IdentReceiveTimeout)
      stay() using Data.Reconnect(sendConnectRequest(), System.currentTimeMillis())

    case Event(Terminated(actor), _) =>
      logger.error("Terminated")
      testAuthEnabled(ExitCode.ConnectingTerminated)
      stay()

    case Event(ActivityCheck, reconnect: Data.Reconnect) =>
      if(System.currentTimeMillis() - reconnect.lastActivity > 10000) {
        logger.error(s"Connecting Inactive timeout! ${reconnect.wsClient} Trying reconnect..")
        testAuthEnabled(ExitCode.ConnectionTimeout)
        stay() using Data.Reconnect(sendConnectRequest(), System.currentTimeMillis())
      } else stay()

    case Event(ex: WSException, _) =>
      logger.info(s"Catch exception ${ex.e.getMessage}")
      testAuthEnabled(ExitCode.CatchException)
      stay()
  }

  when(State.Registering) {
    case Event(WsClient.Connected, _) =>
      logger.error("WTF?")
      testAuthEnabled(ExitCode.WTF)
      context.setReceiveTimeout(Duration.Undefined)
      self ! Warmup
      stay()

    case Event(Warmup, data: Data.ConnectionEstablished) =>
      val terminalName = settings.autoLoginConfig.terminal
      val login = settings.autoLoginConfig.username
      val password = settings.autoLoginConfig.password
      logger.info(s"Register access control terminal ${data.wsClient}")
      data.wsClient ! RegisterTerminal(login, password, terminalName, settings.organisationId)
      stay() using data.copy(lastActivity = Instant.now().getEpochSecond)

    case Event(TerminalRegistered(id, uid), Data.ConnectionEstablished(a, l)) =>
      logger.info(s"Terminal registered on access control server as id `${id.value}`")
      testAuthEnabled(ExitCode.TerminalRegistered)
      goto(State.Working) using Data.Working(id, uid, a, l)

    case Event(AccessDenied, _) =>
      logger.error(s"Access denied for terminal with name `${settings.autoLoginConfig.terminal}`")
      logger.error("Stopping terminal process..")
      testAuthEnabled(ExitCode.AccessDenied)
      context.system.terminate()
      stop()

    case Event(RegisterError, _) =>
      logger.error("Terminal register error on access control server")
      testAuthEnabled(ExitCode.TerminalRegisterError)
      stay()

    case Event(Ping, data: Data.ConnectionEstablished) =>
      logger.error("reg conn established")
      testAuthEnabled(ExitCode.RegConnEstablished)
      data.wsClient ! Pong
      stay() using data.copy(lastActivity = System.currentTimeMillis())

    case Event(ActivityCheck, data: Data.ConnectionEstablished) =>
      if(System.currentTimeMillis() - data.lastActivity > 10000) {
        logger.error(s"Registering Inactive timeout ${data.wsClient}! Trying reconnect..")
        testAuthEnabled(ExitCode.RegisteringInactiveTimeout)
        goto(State.Connecting) using Data.Reconnect(sendConnectRequest(), System.currentTimeMillis())
      } else {
        stay()
      }

    case Event(Terminated(actor), _) =>
      logger.error(s"Terminated ${actor}. Registering reconnect")
      testAuthEnabled(ExitCode.RegisteringTerminated)
      stay()
  }

  when(State.Working) {

    case Event(p@RemotePrintFile(_, printer, badge, data), workData: Data.Working) =>
      logger.info("Print file command received")
      printerService.print(p) pipeTo sender()
      stay() using workData.copy(lastActivity = System.currentTimeMillis())

    case Event(dr: ReadersActor.DeviceEvent.DataReceived, workData: Data.Working) =>
      ScannersService.dataReceived(injector, workData.id, dr) map { resultOpt =>
        resultOpt.foreach { result =>
          workData.wsClient ! TerminalMessage(workData.uid, result)
        }
      } recover {
        case NonFatal(e) =>
          logger.error("Scanned data processing failed", e)
      }
      stay()

    case Event(ActivityCheck, Data.Working(_, uid, actor, lastActivity)) =>
      if(System.currentTimeMillis() - lastActivity > 10000) {
        logger.error(s"Working Inactive timeout! ${actor} Trying reconnect..")
        goto(State.Connecting) using Data.Reconnect(sendConnectRequest(), System.currentTimeMillis())
      } else {
        stay()
      }

    case Event(_: ReadersActor.DeviceEvent.WriteSuccess, workData: Data.Working) =>
      workData.wsClient ! TerminalMessage(workData.uid,
        TerminalEvent.WriteRfidComplete(workData.id))
      stay()

    case Event(wf: ReadersActor.DeviceEvent.WriteFailure, workData: Data.Working) =>
      workData.wsClient ! TerminalMessage(workData.uid,
        TerminalEvent.WriteRfidError(workData.id, wf.error))
      stay()


    case Event(Terminated(actor), _) =>
      logger.error("Terminal worker is down")
      stay()

    case Event(Ping, data: Data.Working) =>
      data.wsClient ! Pong
      stay() using data.copy(lastActivity = System.currentTimeMillis())

    case Event(msg: ServerMessage, data: Data.Working) =>
      logger.info(s"Push from server: ${msg.msg.toString}")
      context.system.eventStream.publish(msg)
      stay() using data.copy(lastActivity = System.currentTimeMillis())

    case Event(unknown, data: Data.Working) =>
      logger.info(s"Unknown event received ${unknown.toString} at state Working")
      stay()

    case unknown =>
      logger.info(s"Unknown message ${unknown.toString}")
      stay()
  }

  def testAuthEnabled(exitCode: Int) = {
    if (injector.settings.testAuthEnabled) {
      sys.exit(exitCode)
    }
  }


}
