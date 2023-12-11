package sevts.terminal.actors.readers

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, PrivateMethodTester}
import sevts.terminal.actors.readers.vlaccess.VLAccessReader

class VCAccessReaderTest extends AnyFlatSpec
  with PrivateMethodTester
  with Matchers
  with LazyLogging
  with BeforeAndAfterAll {

  "Json data" should "read external id" in {
    val json =
      """
        |{
        |    "candidates": [
        |        {
        |            "event_id": null,
        |            "create_time": "2023-10-03T13:50:52.769472+03:00",
        |            "lists": [
        |                "fb7339bf-7d04-4c7a-aeb1-ffd9b3b52e5e"
        |            ],
        |            "account_id": "9e81f3ef-9196-4a32-b1ec-e55debec8f78",
        |            "user_data": "",
        |            "avatar": "/6/samples/faces/c1e46d4d-4898-42b9-aad9-44a479cb1217",
        |            "external_id": "123456789",
        |            "face_id": "123456789"
        |        }
        |    ],
        |    "attributes": {
        |        "ethnicities": {
        |            "predominant_ethnicity": "caucasian",
        |            "estimations": {
        |                "asian": 3.9709528209641576e-05,
        |                "indian": 1.1091918167949189e-05,
        |                "caucasian": 0.9998611211776733,
        |                "african_american": 8.804929530015215e-05
        |            }
        |        },
        |        "age": 38,
        |        "gender": 1
        |    },
        |    "source_device": "hik1_src",
        |    "capture_time": "2023-10-04T16:07:13.233841",
        |    "tag": "55e7ebe3-3c1f-4d6a-be37-be16e880be4c"
        |}
        |""".stripMargin

    val input = VLAccessReader.readJson(json)

    logger.info(input.toString)
    input.candidates.head.external_id.shouldBe("123456789")
  }

  "Empty data" should "empty" in {
    val json =
      """
        |{
        |    "candidates": [],
        |    "attributes": {
        |        "ethnicities": {
        |            "predominant_ethnicity": "caucasian",
        |            "estimations": {
        |                "asian": 3.9709528209641576e-05,
        |                "indian": 1.1091918167949189e-05,
        |                "caucasian": 0.9998611211776733,
        |                "african_american": 8.804929530015215e-05
        |            }
        |        },
        |        "age": 38,
        |        "gender": 1
        |    },
        |    "source_device": "hik1_src",
        |    "capture_time": "2023-10-04T16:07:13.233841",
        |    "tag": "55e7ebe3-3c1f-4d6a-be37-be16e880be4c"
        |}
        |""".stripMargin

    val input = VLAccessReader.readJson(json)

    logger.info(input.toString)
    input.candidates.length.shouldBe(0)
  }

}
