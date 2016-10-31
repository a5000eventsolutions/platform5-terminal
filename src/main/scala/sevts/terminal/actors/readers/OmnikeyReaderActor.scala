package sevts.terminal.actors.readers

import java.io.IOException
import java.util
import java.util.concurrent.TimeUnit
import javax.smartcardio._

import akka.actor.{Actor, ActorRef, Props}
import com.fazecast.jSerialComm._
import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.config.Settings.DeviceConfig
import scala.concurrent.blocking
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal

object OmnikeyReaderActor {
  def props(listener: ActorRef, device: DeviceConfig) = Props(classOf[OmnikeyReaderActor], listener, device)

  sealed trait Command
  object Command {
    case class DataReceived(port: SerialPort) extends Command
    case class StartTerminalRead(terminal: CardTerminal) extends Command
    case class ReadCard(terminal: CardTerminal) extends Command
  }

  case class SerialDataReceived(portName: String, deviceName: String, data: String)

  sealed trait Response
  object Response {
    case class PortOpened(name: String) extends Response
    case class PortClosed(name: String) extends Response
    case object Subscribed extends Response
    case object UnSubscribed extends Response
    case class Error(msg: String) extends Response
  }

}

class OmnikeyReaderActor(listener: ActorRef, device: DeviceConfig) extends Actor with LazyLogging {
  import OmnikeyReaderActor._

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val portName = device.parameters.getString("portName")
  val delay = Duration(device.parameters.getInt("delay"), TimeUnit.MILLISECONDS)


  override def preStart = {
    logger.info("Starting Omnikey reader...")
    connect()
  }

  override def postStop = {
    logger.error("Actor dead")
  }

  def receive = {

    case Command.StartTerminalRead(terminal: CardTerminal) ⇒
      context.system.scheduler.scheduleOnce(delay, self, Command.ReadCard(terminal))

    case Command.ReadCard(terminal) ⇒
      tryReadCard(terminal) foreach { result ⇒
        logger.info(s"Read value: $result")
        listener ! ReadersActor.DeviceEvent.DataReceived(device.name, result)
      }
      context.system.scheduler.scheduleOnce(delay, self, Command.ReadCard(terminal))

    case msg ⇒
      logger.error("Unknown message received ${msg.toString}")
  }

  def connect() = {
    import scala.collection.JavaConversions._
    var selectedTerminal: CardTerminal = null
    try {
      while (selectedTerminal == null) {
        {
          val terminals: CardTerminals = TerminalFactory.getDefault.terminals
          val terminalList: util.List[CardTerminal] = terminals.list
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
            case e: InterruptedException ⇒
              logger.error("Interrupted exception error by connect Omnikey reader")
          }
        }
      }
      self ! Command.StartTerminalRead(selectedTerminal)
    }
    catch {
      case e: Exception ⇒
        logger.error(e.getMessage, e)
        throw new IOException(e.getMessage, e)
    }
  }

  protected def tryReadCard(terminal: CardTerminal): Option[String] = {
    try {
      blocking {
        if (terminal.waitForCardPresent(0)) {
          logger.info("RFID card found...")
          val card: Card = terminal.connect("*")
          val response: ResponseAPDU = card.getBasicChannel.transmit(new CommandAPDU(Array[Byte](0xFF.toByte, 0xCA.toByte, 0x00, 0x00, 0x00)))
          val result = bytArrayToHex(response.getBytes)
          Some(result)
        } else None
      }
    }
    catch {
      case e: CardException ⇒
        logger.error(e.getMessage, e)
        None
      case e: Throwable if NonFatal(e) ⇒
        logger.error(e.getMessage, e)
        None
    }
  }

  private def bytArrayToHex(a: Array[Byte]): String = {
    a.map("%02X" format _).mkString
  }

  def bytearray2intarray(barray: Array[Byte]) = {
    barray.map(b ⇒ b & 0xff)
  }
}
