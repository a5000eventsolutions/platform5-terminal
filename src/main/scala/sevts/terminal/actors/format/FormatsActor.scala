package sevts.terminal.actors.format

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorRef, Props}
import sevts.terminal.config.Settings
import sevts.terminal.config.Settings.{FormatConfig, FormatType, ScannerConfig}

object FormatsActor {

  def props(settings: Settings) = {
    Props(classOf[FormatsActor], settings)
  }

  sealed trait Request
  object Request {
    case class Process(scannerConfig: ScannerConfig, data: String) extends Request
  }

  sealed trait Processed
  object Processed {
    case class StringData(value: String) extends Processed
    case class NumericData(value: Double) extends Processed
    case class ObjectData(fields: Map[String, String]) extends Processed
  }
}

class FormatsActor(settings: Settings) extends Actor {
  import FormatsActor._

  private var actors = Map[FormatType, ActorRef]()

  override def preStart() = {
    settings.terminalConfig.formats.foreach { (format: FormatConfig) ⇒
      actors += format.driverType → (format.driverType match {
        case FormatType.Plain ⇒ context.actorOf(PlainFormatActor.props(format))
      })
    }
  }

  override def receive: Receive = {
    case Request.Process(scannerConfig,data) ⇒
        actors(scannerConfig.format.driverType).tell(FormatActor.Request.Process(data), sender())
  }
}
