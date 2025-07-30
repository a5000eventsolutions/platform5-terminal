package sevts.terminal.actors.readers

import com.typesafe.scalalogging.LazyLogging

import java.nio.charset.StandardCharsets
import javax.smartcardio.{CardChannel, CardTerminal, CommandAPDU, ResponseAPDU}

trait OmnikeyWriteHelper extends LazyLogging {

  protected def bytesToHex(a: Array[Byte]): String = a.map(b => f"${b & 0xFF}%02X").mkString
  protected def hexToBytes(hex: String): Array[Byte] = hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  protected def ensureOk(resp: ResponseAPDU, what: String): Boolean = {
    val ok = resp.getSW == 0x9000
    if (!ok) logger.error(s"$what failed, SW=${resp.getSW.toHexString.toUpperCase}")
    ok
  }

  /** Build a single-record NDEF URI message (TNF=Well Known, Type='U'). */
  protected def buildNdefUri(url: String): Array[Byte] = {

    val prefixes = List(
      "http://www." -> 0x01,
      "https://www." -> 0x02,
      "http://" -> 0x03,
      "https://" -> 0x04
    )

    val (prefix, code) = prefixes.find { case (p, _) => url.toLowerCase.startsWith(p) }
      .map { case (p, c) => (p, c.toByte) }
      .getOrElse(("", 0x00.toByte))

    val rest = if (prefix.isEmpty) url else url.substring(prefix.length)
    val restBytes = rest.getBytes(StandardCharsets.UTF_8)

    val payloadLen = 1 + restBytes.length // 1 byte for prefix code

    // NDEF short record (SR=1): D1 01 <PLEN> 55 <code> <url-bytes>
    val header: Array[Byte] = Array(0xD1.toByte, 0x01.toByte, payloadLen.toByte, 0x55.toByte)
    header ++ Array(code) ++ restBytes
  }

  /** Wrap an NDEF message into TLV: 0x03 <len> <ndef> 0xFE. */
  protected def wrapInTlv(ndef: Array[Byte]): Array[Byte] = {
    if (ndef.length < 0xFF) {
      Array(0x03.toByte, ndef.length.toByte) ++ ndef ++ Array(0xFE.toByte)
    } else {
      // Extended length: 0x03 0xFF <hi> <lo> ... 0xFE
      val hi = ((ndef.length >> 8) & 0xFF).toByte
      val lo = (ndef.length & 0xFF).toByte
      Array(0x03.toByte, 0xFF.toByte, hi, lo) ++ ndef ++ Array(0xFE.toByte)
    }
  }

  /** Compute sector layout for a given absolute block index. Supports 1K and 4K. */
  protected final case class SectorPos(sector: Int, blockInSector: Int, trailerBlockInSector: Int)

  protected def sectorPosForBlock(block: Int): SectorPos = {
    if (block < 128) { // 1K space (or first 32 sectors of 4K)
      SectorPos(block / 4, block % 4, 3)
    } else { // 4K extra sectors: 16 blocks per sector
      val rel = block - 128
      SectorPos(32 + (rel / 16), rel % 16, 15)
    }
  }

  /** Authenticate the given absolute block with Key A. */
  protected def authA(ch: CardChannel, absBlock: Int, keyHex: String, keySlot: Byte): Boolean = {
    val key = hexToBytes(keyHex)
    // Load key into volatile store, slot = keySlot
    val loadKey = new CommandAPDU(Array[Byte](
      0xFF.toByte, 0x82.toByte, 0x20.toByte, 0x00.toByte, 0x06.toByte
    ) ++ key)
    if (!ensureOk(ch.transmit(loadKey), s"Load KeyA for block $absBlock")) return false

    val keyType: Byte = 0x60.toByte // Key A
    val auth = new CommandAPDU(Array[Byte](
      0xFF.toByte, 0x86.toByte, 0x00.toByte, 0x00.toByte, 0x05.toByte,
      0x01.toByte, 0x00.toByte, absBlock.toByte, keyType, keySlot
    ))
    ensureOk(ch.transmit(auth), s"AuthA block $absBlock")
  }

  /** Write a 16-byte block (absolute block index). Assumes we've just authenticated that sector. */
  protected def writeBlock(ch: CardChannel, absBlock: Int, data: Array[Byte]): Boolean = {
    val padded = java.util.Arrays.copyOf(data, 16)
    val apdu = new CommandAPDU(Array[Byte](0xFF.toByte, 0xD6.toByte, 0x00.toByte, absBlock.toByte, 0x10.toByte) ++ padded)
    ensureOk(ch.transmit(apdu), s"Write block $absBlock")
  }

  protected def readBlock(ch: CardChannel, block: Int): Option[Array[Byte]] = {
    val r = ch.transmit(new CommandAPDU(Array[Byte](0xFF.toByte,0xB0.toByte,0x00.toByte,block.toByte,0x10.toByte)))
    if (!ensureOk(r, s"Read block $block")) None else Option(r.getData).filter(_.length == 16)
  }

  /** Quick MAD check: returns true if MAD (sector 0 blocks 1–2) contains at least one 0x03E1/0xE103 pair. */
  protected def isNdefFormattedByMAD(terminal: CardTerminal, keySlot: Byte,
                                     madKeyAHex: String = "A0A1A2A3A4A5",
                                     factoryKeyHex: String = "FFFFFFFFFFFF"): Boolean = {
    try {
      if (!terminal.waitForCardPresent(0)) return false
      val card = terminal.connect("*")
      val ch = card.getBasicChannel

      def readMadBlock(b: Int): Option[Array[Byte]] = {
        if (authA(ch, b, madKeyAHex, keySlot) || authA(ch, b, factoryKeyHex, keySlot)) readBlock(ch, b) else None
      }

      val b1Opt = readMadBlock(1)
      val b2Opt = readMadBlock(2)
      (b1Opt, b2Opt) match {
        case (Some(b1), Some(b2)) =>
          val bytes = b1.drop(2) ++ b2
          val pairs = bytes.grouped(2)
          val cnt = pairs.count { arr =>
            arr.length == 2 && {
              val a = arr(0) & 0xFF; val b = arr(1) & 0xFF
              (a == 0xE1 && b == 0x03) || (a == 0x03 && b == 0xE1)
            }
          }
          cnt > 0
        case _ => false
      }
    } catch { case _: Throwable => false }
  }

  protected def loadKeyNV(ch: CardChannel,
                          keyHex: String,
                          keySlot: Byte): Boolean = {
    val capdu = new CommandAPDU(Array[Byte](0xFF.toByte,0x82.toByte,0x20.toByte,keySlot,0x06.toByte) ++ hexToBytes(keyHex))
    ensureOk(ch.transmit(capdu), s"LoadKey $keyHex")
  }

  protected def authWith(ch: CardChannel,
                         absBlock: Int,
                         keyHex: String,
                         useKeyA: Boolean,
                         keySlot: Byte): Boolean = {
    if (!loadKeyNV(ch, keyHex, keySlot)) return false
    val keyType: Byte = if (useKeyA) 0x60.toByte else 0x61.toByte
    val apdu = new CommandAPDU(Array[Byte](0xFF.toByte,0x86.toByte,0x00.toByte,0x00.toByte,0x05.toByte, 0x01.toByte,0x00.toByte, absBlock.toByte, keyType, keySlot))
    ensureOk(ch.transmit(apdu), s"Auth ${if (useKeyA) "A" else "B"} block $absBlock")
  }


  // MAD builder (MSB,LSB pairs: 03 E1), CRC8 with init 0xC7
  protected case class Mad1(b1:Array[Byte], b2:Array[Byte])

  protected def crc8MadInit(init:Int)(bytes:Array[Byte]): Byte = {
    var crc=init & 0xFF
    bytes.foreach{ b=>
      var cur=b & 0xFF;
      var i=0;
      while(i<8){
        val mix=((crc ^ cur) & 0x80)!=0;
        crc=((crc<<1)&0xFF);
        if(mix) crc^=0x1D;
        cur=(cur<<1)&0xFF; i+=1
      }
    };
    crc.toByte
  }

  protected def buildMadFull_NfcTools(madInfoByte: Byte): Mad1 = {
    val b1 = Array.fill[Byte](16)(0x00.toByte)
    val b2 = Array.fill[Byte](16)(0x00.toByte)
    b1(1) = madInfoByte
    val MSB:Byte = 0x03.toByte; val LSB:Byte = 0xE1.toByte
    for(s <- 1 to 15){
      if(s <= 7){ val off=2 + 2*(s-1); b1(off)=MSB; b1(off+1)=LSB }
      else { val off=2*(s-8); b2(off)=MSB; b2(off+1)=LSB }
    }
    val crcCalc = crc8MadInit(0xC7)(b1.slice(1,16) ++ b2)
    b1(0) = crcCalc
    Mad1(b1,b2)
  }
}
