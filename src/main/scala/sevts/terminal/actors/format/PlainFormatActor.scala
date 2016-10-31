package sevts.terminal.actors.format

import java.util.regex.Pattern

import akka.actor.{Actor, Props}
import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.actors.format.FormatsActor.Processed
import sevts.terminal.config.Settings.FormatConfig

object PlainFormatActor {
  def props(format: FormatConfig): Props = {
    Props(classOf[PlainFormatActor], format)
  }
}

class PlainFormatActor(config: FormatConfig) extends Actor with LazyLogging {
  import FormatActor._

  override def receive: Receive = {
    case Request.Process(value) ⇒
      sender() ! process(value)
  }

  def process(value: String) = {
    val result = if(config.parameters.hasPath("regexp")) {
      val regexp = config.parameters.getString("regexp").r
      value match {
        case regexp(id) ⇒
          logger.info(s"Regex found id: $id")
          id
        case _ ⇒
          logger.info("Regex id not found")
          value
      }
    } else value
    Response.Result(Processed.StringData(result))
  }
}
