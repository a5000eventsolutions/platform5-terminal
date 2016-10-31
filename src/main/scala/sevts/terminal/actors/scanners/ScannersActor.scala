package sevts.terminal.actors.scanners

import akka.actor.{Actor, Props}
import akka.pattern._
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import sevts.terminal.RootActors
import sevts.terminal.actors.format.{FormatActor, FormatsActor}
import sevts.terminal.config.Settings
import sevts.server.domain.{FormField, Id}
import sevts.server.remote.Message.SerialPortMessage
import sevts.server.remote.Reaction

import collection.JavaConverters._
import scala.concurrent.Future

object ScannersActor {

  def props(settings: Settings, rootActors: RootActors): Props = {
    Props(classOf[ScannersActor], settings, rootActors)
  }

  sealed trait Request
  object Request {
    case class DataReceived(deviceName: String, data: String) extends Request
    case class EPCReceived(deviceName: String, data: Array[Byte]) extends Request
  }

  sealed trait Response
  object Response {
    case class DataProcessed(msg: SerialPortMessage) extends Response
    case object Failure extends Response
  }
}

class ScannersActor(settings: Settings, rootActors: RootActors) extends Actor with LazyLogging {
  import ScannersActor._

  implicit val ec = context.dispatcher
  implicit val timeout = Timeout(250.millis)

  override def receive: Receive = {
    case Request.DataReceived(deviceName, data) ⇒
      val scanners = settings.terminalConfig.scanners
      (scanners.find(s ⇒ s.device.name == deviceName) match {
        case Some(scanner) ⇒
          (rootActors.formatsActor ? FormatsActor.Request.Process(scanner, data)) map {
            case FormatActor.Response.Result(processed) ⇒
              val processedValue = processed match {
                case FormatsActor.Processed.NumericData(value) ⇒ value.toString
                case FormatsActor.Processed.StringData(value) ⇒ value
                case e: Any ⇒ throw new IllegalStateException("Invalid terminal format; last format in " +
                  s"list should return either number or string, `$e` has been returned instead")
              }

              val badgeSearch = if(scanner.parameters.hasPath("badgeSearch")) {
                scanner.parameters.getBoolean("badgeSearch")
              } else false

              Response.DataProcessed(SerialPortMessage(
                reaction = scanner.reaction.toReaction,
                fieldId = scanner.parameters.getString("dataField"),
                formId = scanner.parameters.getString("formId"),
                value = processedValue,
                badgeSearch = Some(badgeSearch),
                formList = scanner.parameters.getStringList("formList").asScala.map(id ⇒ Id.M[FormField](id))
              ))
          }
        case None ⇒
          logger.info(s"Unable to find scanner associated with device $deviceName")
          Future(Response.Failure)
      }) pipeTo sender()

    case Request.EPCReceived(deviceName, data) ⇒
      val scanners = settings.terminalConfig.scanners
      val stringData = data.map(_.toChar).mkString
      (scanners.find(s ⇒ s.device.name == deviceName) match {
        case Some(scanner) ⇒
          (rootActors.formatsActor ? FormatsActor.Request.Process(scanner, stringData)) map {
            case FormatActor.Response.Result(processed) ⇒
              val processedValue = processed match {
                case FormatsActor.Processed.NumericData(value) ⇒ value.toString
                case FormatsActor.Processed.StringData(value) ⇒ value
                case e: Any ⇒ throw new IllegalStateException("Invalid terminal format; last format in " +
                  s"list should return either number or string, `$e` has been returned instead")
              }

              val badgeSearch = if(scanner.parameters.hasPath("badgeSearch")) {
                scanner.parameters.getBoolean("badgeSearch")
              } else false

              Response.DataProcessed(SerialPortMessage(
                reaction = Reaction.OpenFormData,
                fieldId = scanner.parameters.getString("dataField"),
                formId = scanner.parameters.getString("formId"),
                value = processedValue,
                badgeSearch = Some(badgeSearch),
                formList = Seq.empty[Id.M[FormField]]
              ))
          }
        case None ⇒
          logger.info(s"Unable to find scanner associated with device $deviceName")
          Future(Response.Failure)
      }) pipeTo sender()
  }
}
