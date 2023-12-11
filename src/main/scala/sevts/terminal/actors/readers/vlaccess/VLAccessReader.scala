package sevts.terminal.actors.readers.vlaccess

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker


object VLAccessReader {

  case class Candidate(external_id: String)
  case class VLAccessData(candidates: Seq[Candidate])

  implicit val condidateCodec: JsonValueCodec[Candidate]  = JsonCodecMaker.make

  implicit val wrapperCodec: JsonValueCodec[VLAccessData] = JsonCodecMaker.make

  def parseData(data: String) = {
    if(data == "No events") Left("none")
    else Right(readJson(data).candidates.headOption)
  }

  def readJson(json: String) = {
    readFromString[VLAccessData](json)
  }

}
