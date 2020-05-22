package sevts.terminal.modules.readers

import java.nio.{Buffer, ByteBuffer, IntBuffer}
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Props}
import com.rfid.rru9809.ComRfidRru9809Library
import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.modules.readers.Rfid9809ReaderActor.Commands._
import sevts.terminal.modules.readers.Rfid9809ReaderActor.Response.{WriteError, WriteOk}
import sevts.terminal.modules.readers.SerialPortReader.Command.DataReceived
import sevts.terminal.modules.scanners.ScannersActor.Request.EPCReceived
import sevts.terminal.config.Settings.DeviceConfig

import scala.annotation.tailrec
import scala.concurrent.blocking
import scala.concurrent.duration.Duration

object Rfid9809ReaderActor {

  def props(listener: ActorRef, config: DeviceConfig): Props = {
    Props(classOf[Rfid9809ReaderActor], listener, config)
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
  }

  sealed trait Response
  object Response {
    case object WriteOk extends Response
    case object WriteError extends Response
  }

  sealed trait Event
  object Event {

  }
}

class Rfid9809ReaderActor(listener: ActorRef, device: DeviceConfig) extends Actor with LazyLogging {

  import Rfid9809ReaderActor._

  val rfid = ComRfidRru9809Library.INSTANCE

  implicit val ec = context.dispatcher

  val delay = Duration(device.parameters.getInt("delay"), TimeUnit.MILLISECONDS)
  val writeTimeout = Duration(device.parameters.getInt("writeTimeout"), TimeUnit.MILLISECONDS)

  val comPort = connect()
  var epcTag: Array[Byte] = null


  override def preStart(): Unit = {
    self ! ReadEPC
  }

  override def receive: Receive = {

    case ReadEPC =>
      readEPCTag(comPort) map { epcTag =>
        listener ! ReadersActor.DeviceEvent.EPCReceived(device.name, epcTag)
        self ! ReadTID
      } getOrElse  {
        context.system.scheduler.scheduleOnce(delay, self, ReadEPC)
      }

    case ReadTID =>
      readTID(comPort, epcTag, 15) map { (data: String) =>
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
        if(result == 0) {
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
    val portAddr=IntBuffer.allocate(1)
    //val errorCode = rfid.AutoOpenComPort(portAddr, comAddr, 5.toByte, frmHandle)
    val port  = device.parameters.getInt("port")
    val errorCode = rfid.OpenComPort(port, comAddr, 5.toByte, frmHandle)
    if(errorCode != 0) {
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
    if(result == 1) {
      logger.info("Rfid EPC read success")
      val length = cleanedEPC.get(0)
      val EPCTag = Array.fill[Byte](length)(0)
      cleanedEPC.position(1)
      cleanedEPC.get(EPCTag, 0, length)
      logger.info(EPCTag.map(_.toChar).mkString)
      //EPCTag.map("%02X" format _).mkString)
      this.epcTag = EPCTag
      Some(EPCTag)
    } else if(result != 251){
      None
    } else None
  }


  @tailrec
  private def readTID(comPort: ComPort, epcTag: Array[Byte], counter: Int): Option[String] = {

    if(counter > 0) {
      val TIDLength = 8

      val TIDmem = 0x02.toByte
      val password = ByteBuffer.allocate(8)
      val wordPtr = 0.toByte
      val num = 4.toByte
      val maskAddr = 0.toByte
      val maskLen = 0.toByte
      val maskFlag = 0.toByte
      val TIDOut = ByteBuffer.allocate(64)//TIDLength)
      val EPCLength = epcTag.length.toByte
      val errorCode = IntBuffer.allocate(1)
      val result = rfid.ReadCard_G2(comPort.comAddr,
        ByteBuffer.wrap(epcTag), TIDmem, wordPtr, num, password,
        maskAddr, maskLen, maskFlag, TIDOut, EPCLength, errorCode,
        comPort.frmHandle)
      if(result == 0) {
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
    if(counter > 0) {
      val password = ByteBuffer.allocate(8)
      val writeDataLen = data.length.toByte
      val writeData = ByteBuffer.wrap(data.toArray)
      val errorCode = IntBuffer.allocate(1)

      val result = rfid.WriteEPC_G2(comPort.comAddr, password, writeData, writeDataLen, errorCode, comPort.frmHandle)
      if(result != 0) {
        if(result != 251) {
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
