package sevts.terminal.actors.readers

import com.cuadernoinformatica.nfc.MifareClassicReaderWriter
import com.typesafe.scalalogging.LazyLogging

import javax.smartcardio.{CardException, CardTerminal}
import scala.concurrent.blocking
import scala.util.control.NonFatal

object OmnikeyWriter extends LazyLogging {

  private val DefaultKeyHex = "ffffffffffff"

  def writeUserData(terminal: CardTerminal,
                    sector: Int,
                    data: String,
                    keyHex: String = DefaultKeyHex,
                    useKeyA: Boolean = true
                   ): Option[Boolean] = {
    try {
      blocking {
        val mcrw = new McrwWithTerminal(terminal)

        // библиотека сама подождёт/подключится через readCard(), используя переданный терминал
        logger.info(s"readCard... ")
        mcrw.readCard()

        val keyType = if (useKeyA) MifareClassicReaderWriter.KEY_A else MifareClassicReaderWriter.KEY_B
        logger.info(s"loading key... keyType: $keyType")
        mcrw.loadKey(keyType, keyHex)

        logger.info(s"readCardInfo: ${mcrw.readCardInfo()}")

        logger.info(s"writeSectorString... sector: $sector, data: $data")
        mcrw.writeSectorString(sector, data)

        logger.info(s"readSectorString...")
        val back = mcrw.readSectorString(sector)
        mcrw.disconnect()
        logger.info(s"back: $back")
        if(back == data) Some(true) else None
      }
    } catch {
      case e: CardException =>
        logger.error(s"CardException: ${e.getMessage}")
        None
      case NonFatal(e) =>
        logger.error(s"NonFatal error e: ${e.getMessage}")
        None
    }
  }

  // ---------------- helpers ----------------

  private def padTo16(bytes: Array[Byte]): Array[Byte] = {
    if (bytes.length == 16) bytes
    else {
      val arr = Array.fill[Byte](16)(0x00.toByte)
      System.arraycopy(bytes, 0, arr, 0, math.min(bytes.length, 16))
      arr
    }
  }

  private def toHex(bytes: Array[Byte]): String =
    bytes.map("%02x".format(_)).mkString

  /**
   * Абсолютный номер блока для MIFARE Classic 1K/4K.
   * Первые 32 сектора по 4 блока, далее по 16 блоков на сектор.
   */
  private def toAbsoluteBlock(sector: Int, blockInSector: Int): Int = {
    require(blockInSector >= 0, "blockInSector must be >= 0")
    if (sector < 32) sector * 4 + blockInSector
    else 32 * 4 + (sector - 32) * 16 + blockInSector
  }
}

class McrwWithTerminal(private val term: CardTerminal) extends MifareClassicReaderWriter {
  override def getTerminal(): CardTerminal = term
}
