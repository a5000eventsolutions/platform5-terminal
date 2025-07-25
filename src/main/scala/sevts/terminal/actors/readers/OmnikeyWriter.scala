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

}

class McrwWithTerminal(private val term: CardTerminal) extends MifareClassicReaderWriter {
  override def getTerminal(): CardTerminal = term
}
