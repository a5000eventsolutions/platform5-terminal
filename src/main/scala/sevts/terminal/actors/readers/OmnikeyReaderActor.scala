package sevts.terminal.actors.readers

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.smartcardio._
import akka.actor.{Actor, ActorRef, Props}
import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.config.Settings.DeviceConfig

import scala.concurrent.blocking
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try
import scala.util.control.NonFatal

object OmnikeyReaderActor {
  def props(listener: ActorRef, device: DeviceConfig) = Props(classOf[OmnikeyReaderActor], listener, device)

  sealed trait Command
  object Command {
    //case class DataReceived(port: SerialPort) extends Command
    case class StartTerminalRead(terminal: CardTerminal) extends Command
    case class ReadCard(terminal: CardTerminal) extends Command
    case class WriteCard(terminal: CardTerminal) extends Command
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
    with LazyLogging
    //with SmartCardOperations
    {

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

    case Command.ReconnectCard =>
      logger.info(s"Reconnect omnikey reader `${portName}`")
      Try(connect()).recover {
        case error =>
          logger.info("Reconnect failed. Try next within 2 seconds.")
          context.system.scheduler.scheduleOnce(2 second, self, Command.ReconnectCard)
      }

    case Command.StartTerminalRead(terminal: CardTerminal) =>
      context.system.scheduler.scheduleOnce(delay, self, Command.ReadCard(terminal))

    case Command.ReadCard(terminal) =>
      tryReadCard(terminal).foreach { result =>
        logger.info(s"Read value: $result")
        listener ! ReadersActor.DeviceEvent.DataReceived(device.name, result.stripSuffix("9000"))
        context.system.scheduler.scheduleOnce(delay, self, Command.WriteCard(terminal))
      }

    case Command.WriteCard(terminal) =>
      val payload = "http://yandfex.ru".getBytes("UTF-8")
      tryWriteMifareClassic(terminal, startBlock = 4, payload).foreach { result =>
        logger.info(s"Write result: $result")
        context.system.scheduler.scheduleOnce(delay, self, Command.ReadCard(terminal))
      }

    case msg =>
      logger.error("Unknown message received ${msg.toString}")
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

  private def bytArrayToHex(a: Array[Byte]): String = {
    a.map("%02X" format _).mkString
  }

  def bytearray2intarray(barray: Array[Byte]) = {
    barray.map(b => b & 0xff)
  }

  /**
   * Write payload to MIFARE Classic card starting from `startBlock`.
   * Splits into 16-byte blocks, skips trailer blocks, authenticates with Key A/B.
   * Returns number of written data blocks wrapped in Some, or None on failure / no card.
   */
  protected def tryWriteMifareClassic(terminal: CardTerminal,
                                      startBlock: Int,
                                      payload: Array[Byte],
                                      key: Array[Byte] = Array.fill(6)(0xFF.toByte), // default Key A
                                       useKeyA: Boolean = true
                                     ): Option[Int] = {
    try {
      blocking {
        if (!terminal.waitForCardPresent(0)) return None

        logger.info("RFID card found (write)...")
        val card = terminal.connect("*")

        try {
          // HID OMNIKEY friendly: try p1=0x20, then 0x00 inside loadKey
          loadKey(card, key, 0x00.toByte)

          val chunks  = payload.grouped(16).toIndexedSeq
          var written = 0

          chunks.indices.foreach { i =>
            val block = startBlock + i
            if (isTrailerBlock(block)) {
              logger.warn(s"Skip trailer block $block")
            } else {
              authenticateClassic(card, block.toByte, useKeyA, 0x00.toByte)
              val data16 =
                if (chunks(i).length == 16) chunks(i).toArray
                else chunks(i).padTo(16, 0.toByte).toArray
              writeBlock(card, block.toByte, data16)
              written += 1
            }
          }

          Some(written)
        } catch {
          case e: CardException =>
            logger.error("MIFARE Classic write failed", e)
            None
          case NonFatal(e) =>
            logger.error("MIFARE Classic write failed (non-fatal)", e)
            None
        } finally {
          try card.disconnect(false) catch { case _: Throwable => () }
        }
      }
    } catch {
      case NonFatal(e) =>
        logger.error("tryWriteMifareClassic: unexpected error", e)
        None
    }
  }

  private def isTrailerBlock(block: Int): Boolean =
    (block + 1) % 4 == 0 // для MIFARE Classic 1K

  private def transmit(card: Card, apdu: Array[Byte]): ResponseAPDU =
    card.getBasicChannel.transmit(new CommandAPDU(apdu))

  private def check(resp: ResponseAPDU, msg: String): Unit = {
    if (resp.getSW != 0x9000) throw new CardException(f"$msg, SW=${resp.getSW}%04X")
  }

  protected def authenticate(card: Card, block: Byte, keyTypeA: Boolean, slot: Byte): Unit = {
    val keyType: Byte = if (keyTypeA) 0x60.toByte else 0x61.toByte
    val apdu = Array[Byte](
      0xFF.toByte, 0x86.toByte, 0x00, 0x00, 0x05,
      0x01, 0x00, block, keyType, slot
    )
    val resp = transmit(card, apdu)
    check(resp, s"AUTH block=$block failed")
  }

  /** Fallback short authenticate (FF 88), used when FF 86 fails with 6982/6986 */
  protected def authenticateFF88(card: Card, block: Byte, keyTypeA: Boolean, slot: Byte): Unit = {
    val keyType: Byte = if (keyTypeA) 0x60.toByte else 0x61.toByte
    val apdu = Array[Byte](0xFF.toByte, 0x88.toByte, 0x00, block, keyType, slot)
    val resp = transmit(card, apdu)
    check(resp, s"AUTH (FF88) block=$block failed")
  }

  /** Try FF 86 first, then fallback to FF 88 if needed */
  protected def authenticateClassic(card: Card, block: Byte, keyTypeA: Boolean, slot: Byte): Unit = {
    try {
      authenticate(card, block, keyTypeA, slot)
    } catch {
      case e: CardException =>
        logger.warn(s"AUTH (FF86) failed for block=$block: ${e.getMessage}; fallback to FF88")
        authenticateFF88(card, block, keyTypeA, slot)
    }
  }

  // FF D6 00 <block> 10 <16 bytes>
  private def writeBlock(card: Card, block: Byte, data16: Array[Byte]): Unit = {
    require(data16.length == 16, "MIFARE Classic block size = 16 bytes")
    val apdu = Array[Byte](0xFF.toByte, 0xD6.toByte, 0x00, block, 0x10.toByte) ++ data16
    val resp = transmit(card, apdu)
    check(resp, s"WRITE block=$block failed")
  }

  /**
   * High-level LOAD KEY that is friendly to HID OMNIKEY:
   *   - tries p1 = 0x20 (volatile key structure) first
   *   - falls back to p1 = 0x00 (ACS-style)
   * Throws CardException if both attempts fail.
   */
  def loadKey(card: Card, key: Array[Byte], slot: Byte): Unit = {
    val attempts = Seq[Byte](0x20.toByte, 0x00.toByte)
    var lastErr: Option[Throwable] = None
    var loaded  = false

    attempts.iterator.takeWhile(_ => !loaded).foreach { p1 =>
      try {
        loadKey(card, key, slot, p1)
        loaded = true
        logger.debug(f"LOAD KEY ok with p1=0x$p1%02X, slot=$slot")
      } catch {
        case e: CardException =>
          lastErr = Some(e)
          logger.warn(f"LOAD KEY failed with p1=0x$p1%02X, will try next")
      }
    }

    if (!loaded) throw lastErr.getOrElse(new CardException("LOAD KEY failed for all key structures (0x20, 0x00)"))
  }

  /** Low-level variant: send FF 82 with explicitly provided key structure (p1). */
  def loadKey(card: Card, key: Array[Byte], slot: Byte, p1: Byte): Unit = {
    // FF 82 <p1:key structure> <slot> 06 <key>
    val apdu = Array[Byte](0xFF.toByte, 0x82.toByte, p1, slot, 0x06) ++ key
    val resp = transmit(card, apdu)
    check(resp, f"LOAD KEY failed (p1=0x$p1%02X, slot=$slot)")
  }


}
