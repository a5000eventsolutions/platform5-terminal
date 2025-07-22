package sevts.terminal.actors.readers

import com.rfid.rru9809.ComRfidRru9809Library
import com.typesafe.scalalogging.LazyLogging

import java.nio.{ByteBuffer, IntBuffer}
import scala.annotation.tailrec

trait Rfid9809Operations extends LazyLogging {

  protected val rfid: ComRfidRru9809Library

  case class ComPort(comAddr: ByteBuffer, frmHandle: Int)

  protected def getRfidString(rfid: Array[Byte]): String = rfid.map("%02X" format _).mkString

  def connect(port: Int) = {
    val comAddr = ByteBuffer.allocate(1)
    val frmHandle = IntBuffer.allocate(1)
    val portAddr = IntBuffer.allocate(1)
    logger.info(s"RFID9809 initialize port $port")
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
      logger.info(s"EPC read success ${getRfidString(EPCTag)}")
      Some(EPCTag)
    } else if(result != 251){
      None
    } else None
  }

  @tailrec
  final def readTID(comPort: ComPort, epcTag: Array[Byte], counter: Int): Option[String] = {
    if(counter > 0) {
      val TIDLength = 8
      val TIDmem = 0x02.toByte
      val password = ByteBuffer.allocate(8)
      val wordPtr = 0.toByte
      val num = 4.toByte
      val maskAddr = 0.toByte
      val maskLen = 0.toByte
      val maskFlag = 0.toByte
      val TIDOut = ByteBuffer.allocate(64)
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
  final def writeEPC(comPort: ComPort, EPCTag: Array[Byte], data: Seq[Byte], counter: Int): Option[Int] = {
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

  @tailrec
  final def writeUser(port: ComPort, epc: Array[Byte], data: Seq[Byte], retries: Int): Option[Int] = {
    if (retries <= 0) return None
    // align to word boundary (16‑bit)
    val padded = if (data.length % 2 == 0) data else data :+ 0x00.toByte
    val wordLen: Byte = (padded.length / 2).toByte
    val writeBuf       = ByteBuffer.wrap(padded.toArray)
    val password  = ByteBuffer.allocate(8)           // 0x00000000 if open
    val written   = IntBuffer.allocate(2)
    val errorCode = IntBuffer.allocate(1)
    val res = rfid.WriteCard_G2(
      port.comAddr,
      ByteBuffer.wrap(epc),
      0x03.toByte,                  // USER bank
      0,                            // wordPtr
      wordLen,
      writeBuf,
      password,
      0.toByte, 0.toByte, 0.toByte, // mask* params
      written,
      epc.length.toByte,
      errorCode,
      port.frmHandle)
    if (res == 0) Some(0) else writeUser(port, epc, data, retries - 1)
  }


}

