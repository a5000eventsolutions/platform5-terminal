package sevts.terminal.actors.readers

import com.typesafe.scalalogging.LazyLogging

import java.nio.charset.StandardCharsets
import javax.smartcardio.{CardChannel, CardException, CardTerminal, CommandAPDU, ResponseAPDU}
import scala.concurrent.blocking

object OmnikeyWriter extends OmnikeyWriteHelper {

  /**
   * Write a URL as an NDEF URI record to a **MIFARE Classic** card
   * that is already formatted for NDEF (per NXP AN1304).
   *
   * Assumptions:
   *  - Starting write position is block 4 (sector 1, block 0).
   *  - MAD (sector 0) is present and sectors for NDEF are accessible.
   *  - Key A for MAD sector = A0A1A2A3A4A5; Key A for NDEF sectors = D3F7D3F7D3F7.
   *  - Card is writable (GPB indicates read/write).
   */
  def writeUrlNdefToMifareClassic(terminal: CardTerminal,
                                  url: String,
                                  keySlot: Byte = 0x00.toByte,
                                  madKeyAHex: String = "A0A1A2A3A4A5",
                                  ndefKeyAHex: String = "D3F7D3F7D3F7",
                                  startBlock: Int = 4 // Block 4 == sector 1, block 0
                                 ): Option[Boolean] = {

    try {
      blocking {
        if (!terminal.waitForCardPresent(0)) return None

        val card = terminal.connect("*")
        val ch = card.getBasicChannel

        val ndef = buildNdefUri(url)
        val tlv  = wrapInTlv(ndef)
        logger.info(s"NDEF length=${ndef.length}, TLV length=${tlv.length}")

        var absBlock = startBlock
        var offset   = 0

        while (offset < tlv.length) {
          val pos = sectorPosForBlock(absBlock)
          // Skip sector trailer blocks
          if (pos.blockInSector == pos.trailerBlockInSector) {
            // Move to the first data block of the next sector
            absBlock = if (absBlock < 128) ((pos.sector + 1) * 4) else {
              // next sector start in 4K tail
              val nextSector = pos.sector + 1
              if (nextSector < 32) nextSector * 4 else {
                // sectors >= 32 use 16 blocks/sector; sector 32 starts at abs block 128
                128 + (nextSector - 32) * 16
              }
            }
          } else {
            // Authenticate once per sector before first write in that sector
            val keyHex = if (pos.sector == 0) madKeyAHex else ndefKeyAHex
            // Authenticate the current block (PC/SC expects absolute block number)
            if (!authA(ch, absBlock, keyHex, keySlot)) return None

            val remaining = tlv.length - offset
            val chunk = math.min(16, remaining)
            val slice = java.util.Arrays.copyOfRange(tlv, offset, offset + chunk)
            if (!writeBlock(ch, absBlock, slice)) return None

            logger.info(s"Wrote block $absBlock: ${bytesToHex(slice)}")

            offset += chunk
            absBlock += 1
          }
        }

        logger.info("Successfully wrote NDEF URI TLV to MIFARE Classic")
        Some(true)
      }
    } catch {
      case e: CardException =>
        logger.error("CardException: " + e.getMessage)
        None
      case e: Throwable =>
        logger.error("Unexpected error: " + e.getMessage)
        None
    }
  }

  def writeUrlNdefAuto(terminal: CardTerminal,
                       url: String,
                       keySlot: Byte = 0x00.toByte
                      ): Option[Boolean] = {
    val alreadyFormatted = isNdefFormattedByMAD(terminal, keySlot)
    if (alreadyFormatted) {
      logger.info("MAD indicates NDEF mapping present → writing TLV only")
      return writeUrlNdefToMifareClassic(terminal, url, keySlot)
    }

    logger.info("MAD indicates no NDEF mapping → formatting (NFC Tools compat) then writing")
    formatClassic1kFullNdef_NfcToolsCompat(terminal, keySlot) match {
      case Some(true) => writeUrlNdefToMifareClassic(terminal, url, keySlot)
      case _ => None
    }
  }




  /** NFC Tools seems to mark MAD with pairs **03 E1** (MSB,LSB), often with MAD[1]=0x01
   * and uses a CRC8 with initial value 0xC7 (which yields the stored byte seen in logs).
   * This formatter mirrors that layout for maximum interoperability with cards
   * initialized via NFC Tools.
   *
   * NOTE: This still does **not** write any NDEF data; it only formats the card.
   */
  protected def formatClassic1kFullNdef_NfcToolsCompat(
                                                        terminal: CardTerminal,
                                                        keySlot: Byte = 0x00.toByte,
                                                        currentKeyHex: String = "FFFFFFFFFFFF",
                                                        madInfoByte: Byte = 0x01.toByte,        // MAD[1] = 0x01 like in your NFC Tools dump
                                                        madGpb: Byte = 0xC1.toByte,             // GPB for MAD sector
                                                        dataGpb: Byte = 0x40.toByte             // Mapping 1.0, RW
                                                      ): Option[Boolean] = {

    try {
      if (!terminal.waitForCardPresent(0)) return None
      val card = terminal.connect("*")
      val ch = card.getBasicChannel

      val sec0b1 = 1; val sec0b2 = 2; val sec0tr = 3

      // 1) MAD blocks with MSB,LSB order
      val mad = buildMadFull_NfcTools(madInfoByte)
      val okB1 = authWith(ch, sec0b1, currentKeyHex, useKeyA=true, keySlot) || authWith(ch, sec0b1, currentKeyHex, useKeyA=false, keySlot)
      if (!okB1) { logger.error("Auth sector0 block1 failed"); return None }
      if (!writeBlock(ch, sec0b1, mad.b1)) return None
      if (!writeBlock(ch, sec0b2, mad.b2)) return None

      // 2) MAD trailer
      val madTrailer = new Array[Byte](16)
      System.arraycopy(hexToBytes("A0A1A2A3A4A5"), 0, madTrailer, 0, 6)
      madTrailer(6)=0x78.toByte; madTrailer(7)=0x77.toByte; madTrailer(8)=0x88.toByte; madTrailer(9)=madGpb
      System.arraycopy(hexToBytes("FFFFFFFFFFFF"), 0, madTrailer, 10, 6)
      val okTR = authWith(ch, sec0tr, currentKeyHex, useKeyA=false, keySlot) || authWith(ch, sec0tr, currentKeyHex, useKeyA=true, keySlot)
      if (!okTR) { logger.error("Auth sector0 trailer failed"); return None }
      if (!writeBlock(ch, sec0tr, madTrailer)) return None

      // 3) Data sectors trailers (public NDEF)
      val access = Array(0x7F.toByte, 0x07.toByte, 0x88.toByte)
      for (s <- 1 to 15){
        val trAbs = s*4 + 3
        val ok = authWith(ch, trAbs, currentKeyHex, useKeyA=false, keySlot) || authWith(ch, trAbs, currentKeyHex, useKeyA=true, keySlot)
        if (!ok) { logger.error(s"Auth sector $s trailer failed"); return None }
        val tr = new Array[Byte](16)
        System.arraycopy(hexToBytes("D3F7D3F7D3F7"), 0, tr, 0, 6)
        System.arraycopy(access, 0, tr, 6, 3)
        tr(9) = dataGpb
        System.arraycopy(hexToBytes("FFFFFFFFFFFF"), 0, tr, 10, 6)
        if (!writeBlock(ch, trAbs, tr)) return None
      }

      logger.info("Classic 1K formatted (NFC Tools compatible MAD). No data written.")
      Some(true)
    } catch { case _:Throwable => logger.error("Formatting (NFC Tools compat) failed"); None }
  }

  /**
   * Log-only checker: determines if a MIFARE Classic **1K** card looks NDEF-formatted.
   * No exceptions are thrown — everything is written to the logger.
   * Heuristics used (NXP AN1304 mapping):
   *  - MAD1 in sector 0 contains AID 0x03E1 (bytes E1 03) for one or more data sectors
   *  - Sector 1 trailer has Access = 7F 07 88 and GPB = 0x40 (public NDEF, mapping 1.0, RW)
   *  - AUTH with KeyA=D3F7... succeeds for sector 1 / block 4
   */
  protected def logNdefStatus1K(terminal: CardTerminal,
                                keySlot: Byte = 0x00.toByte,
                                madKeyAHex: String = "A0A1A2A3A4A5",
                                ndefKeyAHex: String = "D3F7D3F7D3F7",
                                factoryKeyHex: String = "FFFFFFFFFFFF"
                               ): Unit = {

    def crc8Mad(bytes: Array[Byte]): Byte = {
      var crc = 0xE3
      bytes.foreach { b =>
        var cur = b & 0xFF; var i = 0
        while (i < 8) { val mix = ((crc ^ cur) & 0x80) != 0; crc = ((crc << 1) & 0xFF); if (mix) crc ^= 0x1D; cur = (cur << 1) & 0xFF; i += 1 }
      }
      crc.toByte
    }

    if (!terminal.waitForCardPresent(0)) { logger.warn("No card present"); return }
    val card = terminal.connect("*")
    val ch = card.getBasicChannel

    // --- Check 1: try AUTH with public NDEF key on sector 1, block 4 ---
    val canAuthNdefS1 = authA(ch, 4, ndefKeyAHex, keySlot)
    logger.info(s"Auth with NDEF KeyA on block 4: ${if (canAuthNdefS1) "OK" else "FAIL"}")

    // --- Read sector 1 trailer and first data block if possible ---
    val s1Trailer = 7
    val s1TrailerData = if (canAuthNdefS1 || authA(ch, s1Trailer, ndefKeyAHex, keySlot)) readBlock(ch, s1Trailer) else None
    s1TrailerData.foreach { tr =>
      val access = tr.slice(6,9); val gpb = tr(9)
      logger.info(s"S1 trailer: ${bytesToHex(tr)} (access=${bytesToHex(access)}, GPB=${f"0x${gpb & 0xFF}%02X"})")
    }

    val s1b0 = if (canAuthNdefS1) readBlock(ch, 4) else None
    s1b0.foreach { d0 =>
      val head = d0.take(6)
      logger.info(s"S1 B0: ${bytesToHex(d0)}")
      if (d0.nonEmpty && (d0(0) & 0xFF) == 0x03) logger.info("TLV present at block 4 (0x03)")
    }

    // --- Check 2: MAD presence (sector 0 blocks 1-2) ---
    val madB1 = {
      if (authA(ch, 1, madKeyAHex, keySlot)) readBlock(ch, 1)
      else if (authA(ch, 1, factoryKeyHex, keySlot)) readBlock(ch, 1) else None
    }
    val madB2 = {
      if (authA(ch, 2, madKeyAHex, keySlot)) readBlock(ch, 2)
      else if (authA(ch, 2, factoryKeyHex, keySlot)) readBlock(ch, 2) else None
    }

    (madB1, madB2) match {
      case (Some(b1), Some(b2)) =>
        val crcCalc = crc8Mad(b1.slice(1,16) ++ b2)
        val stored = b1(0)
        val pairs = (b1.slice(2,16) ++ b2).grouped(2).toArray
        val a03e1 = pairs.count(p => p.length==2 && (p(0) & 0xFF)==0xE1 && (p(1) & 0xFF)==0x03)
        logger.info(s"MAD b1=${bytesToHex(b1)} b2=${bytesToHex(b2)} | CRC stored=${f"%%02X".format(stored & 0xFF)} calc=${f"%%02X".format(crcCalc & 0xFF)} | AID03E1 entries=$a03e1")
      case _ =>
        logger.warn("MAD blocks 1/2 could not be read with MAD or factory keys")
    }

    // --- Summary decision (heuristic) ---
    val looksFormatted = canAuthNdefS1 && s1TrailerData.exists(tr => tr.slice(6,9).sameElements(Array(0x7F.toByte,0x07.toByte,0x88.toByte)) && tr(9) == 0x40.toByte)
    if (looksFormatted) logger.info("NDEF mapping: LOOKS FORMATTED (public NDEF on sector 1, GPB=0x40)")
    else logger.info("NDEF mapping: NOT FORMATTED (or partially formatted)")
  }


}

