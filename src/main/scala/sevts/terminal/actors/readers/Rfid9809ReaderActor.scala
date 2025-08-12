package sevts.terminal.actors.readers

import akka.actor.{Actor, ActorRef, Props}
import com.rfid.rru9809.ComRfidRru9809Library
import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.Injector
import sevts.terminal.actors.readers.Rfid9809ReaderActor.Commands._
import sevts.terminal.actors.readers.Rfid9809ReaderActor.Response.{WriteError, WriteOk}
import sevts.terminal.config.Settings.DeviceConfig

import java.nio.{ByteBuffer, IntBuffer}
import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.util.Try

object Rfid9809ReaderActor {

  def props(injector: Injector, listener: ActorRef, config: DeviceConfig): Props = {
    Props(new Rfid9809ReaderActor(injector, listener, config))
  }

  case class ComPort(comAddr: ByteBuffer, frmHandle: Int)

  sealed trait Commands

  object Commands {
    case object ConnectRFID extends Commands

    case object ReadEPC extends Commands

    case object WriteEPC extends Commands

    case object ReadEPCTag extends Commands

    case class WriteEPCTag(data: Seq[Byte]) extends Commands

    case object ReadTID extends Commands

    case class WriteEpcData(value: String) extends Commands

    case object EpcWriteTimeOut extends Commands
    case class WriteUserData(bytes: Seq[Byte]) extends Commands
  }

  sealed trait Response

  object Response {
    case object WriteOk extends Response

    case object WriteError extends Response
  }

  sealed trait Event

  object Event {

  }

  def getRfidString(rfid: Array[Byte]) = rfid.map("%02X" format _).mkString

}

class Rfid9809ReaderActor(injector: Injector, listener: ActorRef, device: DeviceConfig) extends Actor with LazyLogging {

  import Rfid9809ReaderActor._

  System.setProperty("java.library.path", injector.settings.usbRelay.dllPath)
  System.setProperty("jna.library.path", injector.settings.usbRelay.dllPath)

  logger.info(s"Starting reader ${device.name}")
  val rfid = ComRfidRru9809Library.INSTANCE

  implicit val ec = context.dispatcher

  val delay = Duration(device.parameters.getInt("delay"), TimeUnit.MILLISECONDS)
  val writeTimeout = Duration(device.parameters.getInt("writeTimeout"), TimeUnit.MILLISECONDS)

  val isReadModeEPC = Try(device.parameters.getBoolean("readEPC")).getOrElse(false)
  val isReadModeTID = Try(device.parameters.getBoolean("readTID")).getOrElse(true)

  val comPort = connect()
  var epcTag: Array[Byte] = null


  override def preStart(): Unit = {
    self ! ReadEPC
    logger.info(s"RFID 9809 started for ${device.name}")
  }

  override def receive: Receive = {

    case ReadEPC =>
      readEPCTag(comPort) map { epcTag =>
        if (isReadModeEPC)
          listener ! ReadersActor.DeviceEvent.DataReceived(device.name, getRfidString(epcTag))
        self ! ReadTID
      } getOrElse {
        context.system.scheduler.scheduleOnce(delay, self, ReadEPC)
      }

    case ReadTID =>
      readTID(comPort, epcTag, 15) map { (data: String) =>
        if (isReadModeTID)
          listener ! ReadersActor.DeviceEvent.DataReceived(device.name, data)
        context.system.scheduler.scheduleOnce(writeTimeout, self, EpcWriteTimeOut)
      } getOrElse {
        context.system.scheduler.scheduleOnce(delay, self, ReadEPC)
      }

    case EpcWriteTimeOut =>
      logger.error("Epc data write timeout")
      context.system.scheduler.scheduleOnce(delay, self, ReadEPC)

    case WriteEpcData(data: String) =>
      writeEPC(comPort, epcTag, data.getBytes.toIndexedSeq, 10) map { result =>
        if (result == 0) {
          sender() ! WriteOk
        } else sender() ! WriteError
      } getOrElse {
        sender() ! WriteError
      }

    case unknown =>
      logger.info(unknown.toString)
  }

  def connect() = {
    val comAddr = ByteBuffer.allocate(1)
    val frmHandle = IntBuffer.allocate(1)
    val portAddr = IntBuffer.allocate(1)
    //val errorCode = rfid.AutoOpenComPort(portAddr, comAddr, 5.toByte, frmHandle)
    val port = device.parameters.getInt("port")
    logger.info(s"RFID9809 initialize port $port")
    val errorCode = rfid.OpenComPort(port, comAddr, 5.toByte, frmHandle)
    if (errorCode != 0) {
      logger.error(s"Error connection to RFID scanner. Code=$errorCode")
    }
    logger.info(s"Rfid port COM$port initialized")
    rfid.SetPowerDbm(comAddr, 0.toByte, frmHandle.get(0))
    ComPort(comAddr, frmHandle.get(0))
  }

  def readEPCTag(comPort: ComPort): Option[Array[Byte]] = {
    val cleanedEPC = ByteBuffer.allocate(62)
    val totalLen = IntBuffer.allocate(8)
    val cardNum = IntBuffer.allocate(8)
    val result = rfid.Inventory_G2(comPort.comAddr, cleanedEPC, totalLen, cardNum, comPort.frmHandle)
    if (result == 1) {
      logger.info("Rfid EPC read success")
      val length = cleanedEPC.get(0)
      val EPCTag = Array.fill[Byte](length)(0)
      cleanedEPC.position(1)
      cleanedEPC.get(EPCTag, 0, length)
      logger.info(EPCTag.map(_.toChar).mkString)
      //EPCTag.map("%02X" format _).mkString
      logger.info(s"EPC read success ${getRfidString(EPCTag)}")
      this.epcTag = EPCTag
      Some(EPCTag)
    } else if (result != 251) {
      None
    } else None
  }


  @tailrec
  private def readTID(comPort: ComPort, epcTag: Array[Byte], counter: Int): Option[String] = {

    if (counter > 0) {
      val TIDLength = 8

      val TIDmem = 0x02.toByte
      val password = ByteBuffer.allocate(8)
      val wordPtr = 0.toByte
      val num = 4.toByte
      val maskAddr = 0.toByte
      val maskLen = 0.toByte
      val maskFlag = 0.toByte
      val TIDOut = ByteBuffer.allocate(64) //TIDLength)
      val EPCLength = epcTag.length.toByte
      val errorCode = IntBuffer.allocate(1)
      val result = rfid.ReadCard_G2(comPort.comAddr,
        ByteBuffer.wrap(epcTag), TIDmem, wordPtr, num, password,
        maskAddr, maskLen, maskFlag, TIDOut, EPCLength, errorCode,
        comPort.frmHandle)
      if (result == 0) {
        val tid = Array.fill[Byte](TIDLength)(0)
        TIDOut.get(tid, 0, TIDLength)
        val data = tid.map("%02X" format _).mkString
        logger.info(s"TID read success $data")
        Some(data)
      } else {
        readTID(comPort, epcTag, counter - 1)
      }
    } else {
      None
    }
  }

  @tailrec
  private def writeEPC(comPort: ComPort, EPCTag: Array[Byte], data: Seq[Byte], counter: Int): Option[Int] = {
    if (counter > 0) {
      val password = ByteBuffer.allocate(8)
      val writeDataLen = data.length.toByte
      val writeData = ByteBuffer.wrap(data.toArray)
      val errorCode = IntBuffer.allocate(1)

      val result = rfid.WriteEPC_G2(comPort.comAddr, password, writeData, writeDataLen, errorCode, comPort.frmHandle)
      if (result != 0) {
        if (result != 251) {
          logger.error(s"Write EPC error. Code - ${result.toString}")
        }
        writeEPC(comPort, EPCTag, data, counter - 1)
      } else {
        Some(result)
      }
    } else {
      None
    }

  }

  def writeCard(comPort: ComPort, EPCTag: Array[Byte], data: Seq[Byte]) = {

    val EPCMem = 0x01.toByte
    val password = ByteBuffer.allocate(8)
    val wordPtr = 0.toByte
    val writeDataLen = data.length.toByte
    val writeData = ByteBuffer.wrap(data.toArray)
    val maskAddr = 0.toByte
    val maskLen = 0.toByte
    val maskFlag = 0.toByte
    val writtenDatNum = IntBuffer.allocate(2)
    val EPCLength = EPCTag.length.toByte
    val errorCode = IntBuffer.allocate(1)

    val result = rfid.WriteCard_G2(comPort.comAddr, ByteBuffer.wrap(EPCTag), EPCMem, wordPtr,
      writeDataLen, writeData, password, maskAddr, maskLen, maskFlag, writtenDatNum,
      EPCLength, errorCode, comPort.frmHandle)
    logger.info(s"EPC write result ${result.toString}")
  }
}
