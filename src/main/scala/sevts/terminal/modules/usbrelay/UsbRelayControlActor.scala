package sevts.terminal.modules.usbrelay

import akka.actor.{Actor, Props}
import com.typesafe.scalalogging.LazyLogging
import sevts.remote.protocol.Protocol.ServerMessage
import sevts.server.accesscontrol.CheckAccessResult
import sevts.server.protocol.TerminalEvent.AccessControlData
import sevts.terminal.Injector

import scala.util.control.NonFatal


object UsbRelayControlActor {

  def props(injector: Injector): Props = {
    Props(classOf[UsbRelayControlActor], injector)
  }

}

class UsbRelayControlActor(injector: Injector) extends Actor with LazyLogging {

  val controller = new UsbRelayController(injector)

  override def preStart(): Unit = {
    if(injector.settings.usbRelay.enabled) {
      try {
        logger.info("Starting UsbRelay controller..")
        try {
          controller.start()
        } catch {
          case NonFatal(e) =>
            logger.error(e.getMessage, e)
            System.exit(-1)
        }
        context.system.eventStream.subscribe(this.self, classOf[ServerMessage])
        logger.info("UsbRelay started successfully")
      } catch {
        case NonFatal(e) =>
          logger.error(e.getMessage, e)
      }
    }
  }

  override def receive = {
    case msg: ServerMessage =>
      logger.info(s"Usb relay command: ${msg.msg.terminalId}")
      msg.msg match {
        case data: AccessControlData =>
          data.data.result match {
            case CheckAccessResult.Allowed => openDoor(data.data.tag, data.data.inputData.head)
            case _ => closeDoor()
          }
        case _ =>
      }

    case unknown =>
      logger.error(s"Unknown tripod message ${unknown.toString}")
  }

  private def openDoor(tag: Option[String], input: String) = {
    tag.map { t =>
      controller.open(t)
    }.getOrElse {
      logger.error("Relay tag is empty. Ignore open command")
    }
  }

  private def closeDoor() = {

  }

}
