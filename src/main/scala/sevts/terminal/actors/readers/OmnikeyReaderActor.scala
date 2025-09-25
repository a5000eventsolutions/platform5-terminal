package sevts.terminal.actors.readers

import akka.actor.{Actor, ActorRef, Props}
import com.typesafe.scalalogging.LazyLogging
import sevts.remote.protocol.Protocol.ServerMessage
import sevts.server.protocol.TerminalEvent.WriteRfidUserMemoryEvent
import sevts.terminal.audio.Audio
import sevts.terminal.config.Settings.DeviceConfig

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.smartcardio._
import scala.annotation.tailrec
import scala.concurrent.blocking
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try
import scala.util.control.NonFatal

object OmnikeyReaderActor {
  def props(listener: ActorRef, device: DeviceConfig) = Props(classOf[OmnikeyReaderActor], listener, device)

  // Pure utility to convert HEX string to decimal string with endianness.
  // Exposed for testing.
  def convertToDec(data: String, endian: String): String = {
    val hex = data
    val bytes = hex.grouped(2).toArray.map(Integer.parseInt(_, 16).toByte)
    val order = endian match {
      case "big-endian"     => java.nio.ByteOrder.BIG_ENDIAN
      case "little-endian"  => java.nio.ByteOrder.LITTLE_ENDIAN
      case _                 => java.nio.ByteOrder.LITTLE_ENDIAN
    }
    // Если little-endian, нужно перевернуть байты для BigInteger
    val bytesForBigInt = if (order == java.nio.ByteOrder.LITTLE_ENDIAN) {
      bytes.reverse
    } else {
      bytes
    }

    val bigInt = new java.math.BigInteger(1, bytesForBigInt) // 1 для положительного числа
    bigInt.toString
  }

  sealed trait Command

  object Command {
    //case class DataReceived(port: SerialPort) extends Command
    case class StartTerminalRead(terminal: CardTerminal) extends Command

    case class ReadCard(terminal: CardTerminal) extends Command

    case object ReconnectCard extends Command
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

class OmnikeyReaderActor(listener: ActorRef, device: DeviceConfig)
  extends Actor
    with LazyLogging {

  import OmnikeyReaderActor._

  implicit val system = context.system
  implicit val ec = context.dispatcher

  private val audio = new Audio()

  val portName = device.parameters.getString("portName")
  val delay = Duration(device.parameters.getInt("delay"), TimeUnit.MILLISECONDS)
  val writeCardTimeout = Duration(device.parameters.getInt("writeCardTimeout"), TimeUnit.MILLISECONDS)
  val writeAttempts = writeCardTimeout.toMillis / delay.toMillis


  override def preStart = {
    logger.info("Starting Omnikey reader...")
    context.system.eventStream.subscribe(self, classOf[ServerMessage])
    connect()
  }

  override def postStop = {
    context.system.eventStream.unsubscribe(self, classOf[ServerMessage])
    audio.close()
    logger.error("Actor dead")
  }

  def ready(terminal: CardTerminal): Receive = {
    case msg: ServerMessage =>
      logger.info(s"ServerMessage: ${msg.msg}")
      msg.msg match {
        case data: WriteRfidUserMemoryEvent =>
          logger.info(s"Omnikey: Write user memory event ${data.value}")
          writeCard(terminal, data.value, writeAttempts.toInt) match {
            case Some(true) =>
              audio.playComplete()
              listener ! ReadersActor.DeviceEvent.WriteSuccess(device.name)
              self ! Command.ReadCard(terminal)

            case _ =>
              audio.playError()
              listener ! ReadersActor.DeviceEvent.WriteFailure(
                device.name,
                s"Failed to write after ${writeAttempts} attempts"
              )
              self ! Command.ReconnectCard
          }

        case _ =>
      }

    case Command.ReadCard(terminal) =>
      tryReadCard(terminal).foreach { result =>
        val stripped = result.stripSuffix("9000")
        val needConvert = Option(device.parameters.getBoolean("convertToDec")).getOrElse(false)
        val converted = if (needConvert) convertToDec(stripped, endianness) else stripped
        logger.info(s"Read value: $result, converted: $converted")
        listener ! ReadersActor.DeviceEvent.DataReceived(device.name, converted)
        context.system.scheduler.scheduleOnce(delay, self, Command.ReadCard(terminal))
      }

    case Command.ReconnectCard =>
      context.system.eventStream.unsubscribe(self, classOf[ServerMessage])
      context.become(receive)
      self ! Command.ReconnectCard

    case msg =>
      logger.error(s"Unknown message received ${msg.toString}")
  }

  def endianness: String = {
    Option(device.parameters.getString("hexEndianess"))
      .getOrElse("little-endian")
  }

  def receive = {

    case Command.ReconnectCard =>
      logger.info(s"Reconnect omnikey reader `${portName}`")
      Try(connect()).recover {
        case error =>
          logger.info("Reconnect failed. Try next within 2 seconds.")
          context.system.scheduler.scheduleOnce(2 second, self, Command.ReconnectCard)
      }

    case Command.StartTerminalRead(terminal: CardTerminal) =>
      context.system.eventStream.subscribe(self, classOf[ServerMessage])
      context.become(ready(terminal))
      context.system.scheduler.scheduleOnce(delay, self, Command.ReadCard(terminal))

    case msg: ServerMessage =>
      logger.info(s"ServerMessage received in base state: ${msg}")

    case msg =>
      logger.error(s"Unknown message received ${msg.toString}")
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
      case e: CardException =>
        logger.error(e.getMessage, e)
        self ! Command.ReconnectCard
        None
      case e: Throwable if NonFatal(e) =>
        logger.error(e.getMessage, e)
        self ! Command.ReconnectCard
        None
    }
  }

  @tailrec
  private def writeCard(terminal: CardTerminal, data: String, counter: Int): Option[Boolean] = {
    if (counter > 0) {
      val result = OmnikeyWriter.writeUrlNdefAuto(terminal, data)
      result match {
        case Some(true) => result
        case _ =>
          logger.error(s"Write nfc error for data: $data, retrying...")
          Thread.sleep(delay.toMillis)
          writeCard(terminal, data, counter - 1)
      }
    } else {
      None
    }
  }

  private def bytArrayToHex(a: Array[Byte]): String = {
    a.map("%02X" format _).mkString
  }

  def bytearray2intarray(barray: Array[Byte]) = {
    barray.map(b => b & 0xff)
  }

}
