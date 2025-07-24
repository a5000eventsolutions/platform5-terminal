package sevts.terminal.actors.readers

import akka.actor.{ActorRef, FSM, Props}
import akka.pattern.pipe
import com.typesafe.scalalogging.LazyLogging
import sevts.remote.protocol.Protocol.ServerMessage
import sevts.server.protocol.TerminalEvent.WriteRfidUserMemoryEvent
import sevts.terminal.config.Settings.DeviceConfig

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.smartcardio._
import scala.concurrent.{Future, blocking}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try
import scala.util.control.NonFatal

object OmnikeyWriteReaderActor {
  def props(listener: ActorRef, device: DeviceConfig) =
    Props(classOf[OmnikeyWriteReaderActor], listener, device)

  sealed trait Command
  object Command {
    case class StartTerminalRead(terminal: CardTerminal) extends Command
    case class ReadCard(terminal: CardTerminal) extends Command
    case object ReconnectCard extends Command
    case class WriteUserData(bytes: Seq[Byte], replyTo: ActorRef) extends Command
  }

  sealed trait Response
  object Response {
    case class Data(uidHex: String) extends Response
    case object WriteOk extends Response
    case object WriteError extends Response
    case class Error(msg: String) extends Response
  }

  sealed trait State
  case object Idle       extends State
  case object WaitCard   extends State
  case object ReadUid    extends State
  case object WriteData  extends State

  sealed trait Data
  case class IdleData(pending: Option[PendingWrite]) extends Data
  case class TerminalData(term: CardTerminal, pending: Option[PendingWrite]) extends Data
  case class CardData(term: CardTerminal, card: Card, uidHex: String, pending: Option[PendingWrite]) extends Data
  case class WriteCtx(term: CardTerminal, card: Card, uidHex: String, job: PendingWrite) extends Data

  final case class PendingWrite(bytes: Seq[Byte], replyTo: ActorRef, expiresAt: scala.concurrent.duration.Deadline)

  private case object Tick
  private case object Retry
  private case class UidReadOk(uidHex: String, card: Card, term: CardTerminal)
  private case class UidReadFail(term: CardTerminal)
  private case class UserWriteDone(result: Boolean, job: PendingWrite)
}

class OmnikeyWriteReaderActor(listener: ActorRef, device: DeviceConfig)
  extends FSM[OmnikeyWriteReaderActor.State, OmnikeyWriteReaderActor.Data]
  with LazyLogging
  with SmartCardOperations {

  import OmnikeyWriteReaderActor._
  import OmnikeyWriteReaderActor.Command._

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val portName = device.parameters.getString("portName")
  val delay = Duration(device.parameters.getInt("delay"), TimeUnit.MILLISECONDS)
  val maxRetries = 5
  val pendingTtl  = 30.seconds

  override def preStart(): Unit = {
    logger.info("Starting Omnikey...")
    context.system.eventStream.subscribe(self, classOf[ServerMessage])
    connect()
    super.preStart()
  }

  override def postStop(): Unit = {
    logger.warn("Omnikey stopped")
  }

  startWith(Idle, IdleData(None))

  when(Idle) {
    case Event(msg: ServerMessage, IdleData(_)) =>
      logger.info(s"Omnikey: Received message ${msg.msg}")
      msg.msg match {
        case data: WriteRfidUserMemoryEvent =>
          logger.info(s"Omnikey: Write user memory event ${data.value}")
          val bytes = data.value.getBytes("UTF-8").toIndexedSeq
          self ! Tick
          goto(WaitCard) using IdleData(Some(mkJob(bytes, sender())))
        case _ =>
          stay()
      }

    case Event(StartTerminalRead(term), IdleData(p)) =>
      readUidAsync(term)
      goto(ReadUid) using TerminalData(term, p)
      
    case Event(TerminalData(term, pending), IdleData(_)) =>
      readUidAsync(term)
      goto(ReadUid) using TerminalData(term, pending)

    case Event(cmd: WriteUserData, IdleData(_)) =>
      self ! Tick
      goto(WaitCard) using IdleData(Some(mkJob(cmd.bytes, cmd.replyTo)))

    case Event(Tick, d @ IdleData(_)) =>
      readTerminalAsync()
      stay using d
  }

  when(WaitCard) {
    case Event(TerminalData(term, pending), _) =>
      readUidAsync(term)
      goto(ReadUid) using TerminalData(term, pending)

    case Event(Retry, d: IdleData) =>
      startSingleTimer("scan", Tick, delay)
      stay using d
  }

  when(ReadUid) {
    case Event(UidReadOk(uid, card, term), TerminalData(_, Some(job))) =>
      if (job.expiresAt.isOverdue()) {
        logger.warn("PendingWrite expired before write, skip")
        card.disconnect(false)
        goto(Idle) using IdleData(None)
      } else {
        writeUserAsync(term, card, job)
        goto(WriteData) using WriteCtx(term, card, uid, job)
      }

    case Event(UidReadOk(uid, card, term), TerminalData(_, None)) =>
      listener ! Response.Data(uid)
      card.disconnect(false)
      goto(Idle) using IdleData(None)

    case Event(UidReadFail(term), TerminalData(_, pending)) =>
      startSingleTimer("scan", Tick, delay)
      goto(WaitCard) using TerminalData(term, pending)
  }

  when(WriteData) {
    case Event(UserWriteDone(true, job), WriteCtx(term, card, uid, _)) =>
      job.replyTo ! Response.WriteOk
      card.disconnect(false)
      goto(Idle) using IdleData(None)

    case Event(UserWriteDone(false, job), WriteCtx(term, card, uid, _)) =>
      job.replyTo ! Response.WriteError
      card.disconnect(false)
      goto(Idle) using IdleData(None)
  }

  whenUnhandled {
    case Event(msg: ServerMessage, d: IdleData) =>
      logger.info(s"Omnikey: Received message ${msg.msg}")
      msg.msg match {
        case data: WriteRfidUserMemoryEvent =>
          logger.info(s"Omnikey: Write user memory event ${data.value}")
          val bytes = data.value.getBytes("UTF-8").toIndexedSeq
          stay using d.copy(pending = Some(mkJob(bytes, sender())))
        case _ =>
          stay()
      }

    case Event(cmd: WriteUserData, d: IdleData) =>
      stay using d.copy(pending = Some(mkJob(cmd.bytes, cmd.replyTo)))

    case Event(ReconnectCard, _) =>
      logger.info(s"Reconnect omnikey reader `$portName`")
      Try(connect()).recover { case _ => context.system.scheduler.scheduleOnce(2.seconds, self, ReconnectCard) }
      stay()

    case Event(Tick, d @ TerminalData(term, _)) =>
      readUidAsync(term)
      stay using d

    case msg =>
      logger.error(s"Unknown message received ${msg.toString}")
      stay()
  }

  onTransition {
    case _ -> Idle => self ! Tick
  }

  initialize()

  private def mkJob(bytes: Seq[Byte], replyTo: ActorRef): PendingWrite =
    PendingWrite(bytes, replyTo, Deadline.now + pendingTtl)

  private def readTerminalAsync(): Unit = Future {
    connectAndSelectTerminal().map { term => TerminalData(term, stateData match {
      case IdleData(p) => p
      case _           => None
    }) }.getOrElse(Retry)
  }.pipeTo(self)

  private def readUidAsync(term: CardTerminal): Unit =
    Future {
      readUid(term)
        .map { case (uid, card) => UidReadOk(uid, card, term) }
        .getOrElse(UidReadFail(term))
    }.recover {
      case e: CardException =>
        logger.error("readUid failed", e); UidReadFail(term)
      case NonFatal(e) =>
        logger.error("readUid failed", e); UidReadFail(term)
    }.pipeTo(self)

  private def writeUserAsync(term: CardTerminal, card: Card, job: PendingWrite): Unit =
    Future {
      val ok = writeUserData(card, job.bytes, maxRetries)
      UserWriteDone(ok, job)
    }.recover {
      case NonFatal(e) =>
        logger.error("writeUserData failed", e)
        UserWriteDone(false, job)
    }.pipeTo(self)

  private def connectAndSelectTerminal(): Option[CardTerminal] = {
    import scala.jdk.CollectionConverters._
    val terms = TerminalFactory.getDefault.terminals.list.asScala
    terms.find(_.getName == portName)
  }

  private def connect() = {
    import scala.jdk.CollectionConverters._
    var selectedTerminal: CardTerminal = null
    try {
      while (selectedTerminal == null) {
        {
          val terminals: CardTerminals = TerminalFactory.getDefault.terminals
          val terminalList = terminals.list.asScala
          for (terminal <- terminalList) {
            logger.info(s"Available terminal: ${terminal.getName}")
            if (portName == terminal.getName) {
              logger.info(s"=== Selected terminal: ${terminal.getName}")
              selectedTerminal = terminal
            }
          }
          try {
            Thread.sleep(1000L)
          }
          catch {
            case e: InterruptedException =>
              logger.error("Interrupted exception error by connect Omnikey reader")
          }
        }
      }
      self ! Command.StartTerminalRead(selectedTerminal)
    }
    catch {
      case e: Exception =>
        logger.error(e.getMessage, e)
        throw new IOException(e.getMessage, e)
    }
  }

}
