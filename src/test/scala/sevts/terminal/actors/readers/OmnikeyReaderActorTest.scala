package sevts.terminal.actors.readers

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OmnikeyReaderActorTest extends AnyFlatSpec with Matchers {

  "convertToDec (little-endian)" should "convert HEX to unsigned decimal correctly" in {
    // Given
    val cases = Seq(
      "7B92AB6A" -> "1789629051",
      "7B07E36A" -> "1793263483",
      "E6C56D81" -> "2171454950"
    )

    // When / Then
    cases.foreach { case (hex, expected) =>
      val actual = OmnikeyReaderActor.convertToDec(hex, "little-endian")
      actual shouldBe expected
    }
  }
}
