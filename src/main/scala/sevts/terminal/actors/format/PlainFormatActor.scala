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
    case Request.Process(value) =>
      sender() ! process(value)
  }

  def process(value: String) = {
     val result = if(config.parameters.hasPath("regexp")) {
     val regexp = config.parameters.getString("regexp")
     val pattern = Pattern.compile(regexp)
     val matcher = pattern.matcher(value)

       if(matcher.find) {
         val id = matcher.group(1)
         logger.info(s"Regex found id: $id")
         id
       }  else {
         logger.info("Regex id not found")
         value
       }
    } else value
    val renderedTemplate = renderTemplate(config.template, result)
    Response.Result(Processed.StringData(renderedTemplate))
  }

  private def renderTemplate(tpl: String, data: String): String = {
    tpl.replace("$data$", data)
  }
}
