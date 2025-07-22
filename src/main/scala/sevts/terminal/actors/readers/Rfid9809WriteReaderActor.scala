package sevts.terminal.actors.readers

import akka.actor.{ActorRef, FSM, Props}
import akka.pattern.pipe
import com.rfid.rru9809.ComRfidRru9809Library
import com.typesafe.scalalogging.LazyLogging
import sevts.remote.protocol.Protocol.ServerMessage
import sevts.server.accesscontrol.CheckAccessResult
import sevts.server.protocol.TerminalEvent.{AccessControlData, WriteRfidUserMemoryEvent}
import sevts.terminal.Injector
import sevts.terminal.config.Settings.DeviceConfig

import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.concurrent.duration.Duration

object Rfid9809WriteReaderActor {

  def props(injector: Injector, listener: ActorRef, config: DeviceConfig): Props = {
    Props(new Rfid9809WriteReaderActor(injector, listener, config))
  }

  final case class WriteUserData(bytes: Seq[Byte], replyTo: ActorRef)

  sealed trait Response
  object Response {
    case object WriteOk extends Response
    case object WriteError extends Response
  }

  sealed trait State
  case object Idle      extends State
  case object ReadEpc   extends State
  case object ReadTid   extends State
  case object WriteUser extends State

  sealed trait Data
  case class IdleData(pending: Option[PendingWrite]) extends Data
  case class EpcData(epc: Array[Byte], pending: Option[PendingWrite]) extends Data
  case class WriteCtx(epc: Array[Byte], tid: String, job: PendingWrite) extends Data

  final case class PendingWrite(bytes: Seq[Byte], replyTo: ActorRef)

  private case class TidReadOk(tid: String, epc: Array[Byte])
  private case class TagCtx(epc: Array[Byte])
  private case object TidReadFail
  private case class UserWriteDone(result: Int, job: PendingWrite)
  private case object Tick
  private case object Retry

}

class Rfid9809WriteReaderActor(injector: Injector, listener: ActorRef, device: DeviceConfig)
  extends FSM[Rfid9809WriteReaderActor.State, Rfid9809WriteReaderActor.Data]
    with LazyLogging
    with Rfid9809Operations {

  import Rfid9809WriteReaderActor._

  System.setProperty("java.library.path", injector.settings.usbRelay.dllPath)
  System.setProperty("jna.library.path", injector.settings.usbRelay.dllPath)

  logger.info(s"Starting reader ${device.name}")
  val rfid = ComRfidRru9809Library.INSTANCE

  implicit val ec = context.dispatcher

  val comPort = connect(device.parameters.getInt("port"))

  private val pollDelay     = Duration(device.parameters.getInt("delay"), TimeUnit.MILLISECONDS)
  private val maxReadRetry  = 15
  private val maxWriteRetry = 5

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[ServerMessage])
    super.preStart()
  }

  startWith(Idle, IdleData(None))

  when(Idle) {
    case Event(msg: ServerMessage, IdleData(_)) =>
      logger.info(s"Received message ${msg.msg}")
      msg.msg match {
        case data: WriteRfidUserMemoryEvent =>
          logger.info(s"Write user memory event ${data.value}")
          val bytes = data.value.getBytes("UTF-8").toIndexedSeq
          self ! Tick
          goto(ReadEpc) using IdleData(Some(PendingWrite(bytes, sender())))
        case _ =>
          stay()
      }

    case Event(WriteUserData(bytes, reply), IdleData(_)) =>
      self ! Tick
      goto(ReadEpc) using IdleData(Some(PendingWrite(bytes, reply)))

    case Event(Tick, d @ IdleData(_)) =>
      readEpcAsync()
      stay using d
  }

  when(ReadEpc) {
    case Event(TagCtx(epc), IdleData(p)) =>
      readTidAsync(epc)
      goto(ReadTid) using EpcData(epc, p)

    case Event(Retry, d: IdleData) =>
      startSingleTimer("scan", Tick, pollDelay)
      stay using d
  }

  when(ReadTid) {
    case Event(TidReadOk(tid, tidEpc), EpcData(epc, Some(job))) if tidEpc.sameElements(epc) =>
      writeUserAsync(epc, job)
      goto(WriteUser) using WriteCtx(epc, tid, job)

    case Event(TidReadOk(_, _), _) =>
      goto(Idle) using IdleData(None)

    case Event(TidReadFail, d: EpcData) =>
      startSingleTimer("scan", Tick, pollDelay)
      goto(ReadEpc) using IdleData(d.pending)
  }

  when(WriteUser) {
    case Event(UserWriteDone(0, job), _) =>
      job.replyTo ! Response.WriteOk
      goto(Idle) using IdleData(None)

    case Event(UserWriteDone(_, job), _) =>
      job.replyTo ! Response.WriteError
      goto(Idle) using IdleData(None)
  }

  whenUnhandled {
    case Event(msg: ServerMessage, d: IdleData) =>
      msg.msg match {
        case data: WriteRfidUserMemoryEvent =>
          val bytes = data.value.getBytes("UTF-8").toIndexedSeq
          stay using d.copy(pending = Some(PendingWrite(bytes, sender())))
        case _ =>
          stay()
      }

    case _ => stay()
  }

  onTransition {
    case _ -> Idle => self ! Tick
  }

  initialize()

  private def readEpcAsync(): Unit =
    Future {
      readEPCTag(comPort).map(TagCtx).getOrElse(Retry)
    }.pipeTo(self)

  private def readTidAsync(epc: Array[Byte]): Unit =
    Future {
      readTID(comPort, epc, maxReadRetry)
        .map(tid => TidReadOk(tid, epc))
        .getOrElse(TidReadFail)
    }.pipeTo(self)

  private def writeUserAsync(epc: Array[Byte], job: PendingWrite): Unit =
    Future {
      writeUser(comPort, epc, job.bytes, maxWriteRetry).getOrElse(-1)
    }.map(res => UserWriteDone(res, job))
      .pipeTo(self)

}
