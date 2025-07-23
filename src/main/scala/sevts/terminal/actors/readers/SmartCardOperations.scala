package sevts.terminal.actors.readers

import com.typesafe.scalalogging.LazyLogging

import javax.smartcardio._
import scala.concurrent.blocking
import scala.util.control.NonFatal

trait SmartCardOperations extends LazyLogging {

  def writeUserData(card: Card, bytes: Seq[Byte], retries: Int): Boolean = {
    try {
      logger.info(s"Write Mifare Classic user data: ${bytes.mkString(",")}")
      writeMifareClassic(card, startBlock = 4, data = bytes.toArray)
    } catch {
      case NonFatal(e) =>
        logger.error("writeUserData error", e)
        false
    }
  }

  def readUid(term: CardTerminal): Option[(String, Card)] = {
    blocking {
      if (!term.waitForCardPresent(0)) return None
      val card = term.connect("*")
      val cmd  = new CommandAPDU(Array[Byte](0xFF.toByte, 0xCA.toByte, 0x00, 0x00, 0x00))
      val resp = card.getBasicChannel.transmit(cmd)
      if (resp.getSW != 0x9000) {
        card.disconnect(false)
        None
      } else {
        val uidHex = bytesToHex(resp.getData)
        Some(uidHex -> card)
      }
    }
  }

  def writeMifareClassic(card: Card, startBlock: Int, data: Array[Byte], key: Array[Byte] = Array.fill(6)(0xFF.toByte), useKeyA: Boolean = true): Boolean = {
    val blocks = data.grouped(16).toIndexedSeq
    try {
      loadKey(card, key, 0x00.toByte)
      blocks.zipWithIndex.foreach { case (chunk, idx) =>
        val block = startBlock + idx
        if (isTrailerBlock(block)) {
          logger.warn(s"Skip trailer block $block")
        } else {
          authenticate(card, block.toByte, useKeyA, 0x00.toByte)
          val padded = if (chunk.length == 16) chunk else chunk.padTo(16, 0.toByte)
          writeBlock(card, block.toByte, padded.toArray)
        }
      }
      true
    } catch {
      case NonFatal(e) =>
        logger.error("MIFARE Classic write failed", e)
        false
    }
  }

  private def isTrailerBlock(block: Int): Boolean = (block + 1) % 4 == 0

  protected def transmit(card: Card, apdu: Array[Byte]): ResponseAPDU =
    card.getBasicChannel.transmit(new CommandAPDU(apdu))

  protected def loadKey(card: Card, key: Array[Byte], slot: Byte): Unit = {
    val apdu = Array[Byte](0xFF.toByte, 0x82.toByte, 0x00, slot, 0x06) ++ key
    val resp = transmit(card, apdu)
    check(resp, "LOAD KEY failed")
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

  protected def writeBlock(card: Card, block: Byte, data16: Array[Byte]): Unit = {
    require(data16.length == 16, "Block must be 16 bytes")
    val apdu = Array[Byte](0xFF.toByte, 0xD6.toByte, 0x00, block, 0x10.toByte) ++ data16
    val resp = transmit(card, apdu)
    check(resp, s"WRITE block=$block failed")
  }

  def writeUltralight(card: Card, startPage: Int, data: Array[Byte]): Boolean = {
    val pages = data.grouped(4).toIndexedSeq
    try {
      pages.zipWithIndex.foreach { case (chunk, idx) =>
        val page = startPage + idx
        val padded = if (chunk.length == 4) chunk else chunk.padTo(4, 0.toByte)
        writePage(card, page.toByte, padded.toArray)
      }
      true
    } catch {
      case NonFatal(e) =>
        logger.error("Ultralight write failed", e)
        false
    }
  }

  protected def writePage(card: Card, page: Byte, data4: Array[Byte]): Unit = {
    require(data4.length == 4, "Page must be 4 bytes")
    val apdu = Array[Byte](0xFF.toByte, 0xD6.toByte, 0x00, page, 0x04.toByte) ++ data4
    val resp = transmit(card, apdu)
    check(resp, s"WRITE page=$page failed")
  }

  protected def check(resp: ResponseAPDU, msg: String): Unit = {
    if (resp.getSW != 0x9000) throw new CardException(f"$msg, SW=${resp.getSW}%04X")
  }

  protected def bytesToHex(a: Array[Byte]): String = a.map("%02X" format _).mkString
}
