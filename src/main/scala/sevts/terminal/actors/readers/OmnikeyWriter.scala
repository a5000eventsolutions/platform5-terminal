package sevts.terminal.actors.readers

import com.cuadernoinformatica.nfc.MifareClassicReaderWriter
import com.typesafe.scalalogging.LazyLogging
import sun.jvm.hotspot.HelloWorld.e

import javax.smartcardio.{Card, CardException, CardTerminal}
import scala.concurrent.blocking
import scala.util.control.NonFatal

object OmnikeyWriter extends LazyLogging {

  private val DefaultKeyHex = "ffffffffffff"

  /**
   * @param terminal    PC/SC терминал
   * @param payload     произвольные данные, которые нужно записать
   * @param keyHex      ключ сектора (по умолчанию FF..FF)
   * @param useKeyA     true → KEY_A, false → KEY_B
   * @param sectors     количество секторов: 16 для 1K, 40 для 4K
   * @param startSector с какого сектора начинать писать (обычно 1, чтобы не трогать сектор 0)
   * @return            true, если всё записали и верифицировали; false — если не хватило места или ошибка
   */
  def writeUserData(terminal: CardTerminal,
                    payload: Array[Byte],
                    keyHex: String = DefaultKeyHex,
                    useKeyA: Boolean = true,
                    sectors: Int = 16,
                    startSector: Int = 1
                   ): Option[Boolean] = {
    try {
      blocking {
        if (!terminal.waitForCardPresent(0)) return None

        val card: Card = terminal.connect("*")
        val mcrw: MifareClassicReaderWriter = new OmnikeyWriter(card)

        val keyType = if (useKeyA) MifareClassicReaderWriter.KEY_A else MifareClassicReaderWriter.KEY_B

        logger.info(s"Write Mifare Classic user data: ${payload.mkString(",")}")
        mcrw.loadKey(keyType, keyHex)

        val chunks = payload.grouped(16).toArray
        val capacityBlocks = (sectors - startSector) * 3 // по 3 пользовательских блока на сектор
        if (chunks.length > capacityBlocks) {
          // места не хватит — можно либо вернуть false, либо частично записать
          logger.warn(s"Not enough space for ${chunks.length} blocks, only ${capacityBlocks} blocks available")
          mcrw.disconnect()
          return None
        }

        var chunkIdx = 0
        var ok = true

        var sector = startSector
        while (sector < sectors && chunkIdx < chunks.length && ok) {
          var blockInSector = 0
          while (blockInSector <= 2 && chunkIdx < chunks.length && ok) {
            val absBlock = toAbsoluteBlock(sector, blockInSector)
            val data16   = padTo16(chunks(chunkIdx))
            val hex      = toHex(data16)

            mcrw.writeBlockHexString(absBlock, hex)
            val back = mcrw.readBlockHexString(absBlock)
            if (!back.equalsIgnoreCase(hex)) ok = false else chunkIdx += 1

            blockInSector += 1
          }
          sector += 1
        }

        logger.info("Disconnecting from card")
        mcrw.disconnect()

        val result = ok && chunkIdx == chunks.length
        logger.info(s"Write operation completed: $result (ok=$ok, written blocks=$chunkIdx/${chunks.length})")
        if (result) Some(true) else None
      }
    } catch {
      case e: CardException =>
        // подключите свой логгер/сигнал Reconnect, если требуется
        logger.error("CardException", e)
        None
      case NonFatal(_) =>
        logger.error("NonFatal", e)
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

class OmnikeyWriter(private val c: Card) extends MifareClassicReaderWriter {
  override def getCard: Card = c
}