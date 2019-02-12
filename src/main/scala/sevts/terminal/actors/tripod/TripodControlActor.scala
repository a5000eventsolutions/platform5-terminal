package sevts.terminal.actors.tripod

import akka.actor.{Actor, Props}
import com.typesafe.scalalogging.LazyLogging
import sevts.remote.protocol.Protocol.ServerMessage
import sevts.server.accesscontrol.CheckAccessResult
import sevts.server.actions.Monitor
import sevts.server.protocol.TerminalEvent.AccessControlData
import sevts.terminal.Injector
import sevts.terminal.tripod._

import scala.util.control.NonFatal


object TripodControlActor {

  def props(injector: Injector): Props = {
    Props(classOf[TripodControlActor], injector)
  }

}


class TripodControlActor(injector: Injector) extends Actor with LazyLogging {

  val tripodProps = new TripodProperties(injector.settings)
  val bravoTripod = new BravoTripodImpl()

  val controller = new TripodController(tripodProps, bravoTripod)

  override def preStart(): Unit = {
    if(injector.settings.tripod.enabled) {
      try {
        logger.info("Starting tripod controller..")
        controller.start()
        context.system.eventStream.subscribe(this.self, classOf[ServerMessage])
        logger.info("Tripod started successfully")
      } catch {
        case NonFatal(e) ⇒
          logger.error(e.getMessage, e)
      }
    }
  }

  override def receive = {

    case msg: ServerMessage ⇒
      logger.info(s"Tripod command: ${msg.msg.terminalId}")
      msg.msg match {
        case data: AccessControlData ⇒
          data.data.result match {
            case CheckAccessResult.Allowed ⇒ openDoor(data.data.tag, data.data.inputData.head)
            case _ ⇒ closeDoor()
          }
        case _ ⇒
      }

    case unknown ⇒
      logger.error(s"Unknown tripod message ${unknown.toString}")

  }

  private def openDoor(tag: Option[String], input: String) = {
    val NUMBER_OF_ATTEMPTS = 3
    val status = getTripodDoorStatus(tag, input)
    logger.info(s"Open door direction: ${status.name()}")
    controller.setDoorStatus(status, NUMBER_OF_ATTEMPTS)
  }

  private def getTripodDoorStatus(tagOpt: Option[String], input: String): TripodStatus = {
    tagOpt.flatMap(t ⇒ if(t.isEmpty) None else Some(t)).map { tag ⇒
      if(injector.settings.tripod.directionEnter == tag) { TripodStatus.ENTER }
      else if (injector.settings.tripod.directionExit == tag) { TripodStatus.EXIT }
      else if (injector.settings.tripod.ENTER_ALWAYS == input) { TripodStatus.ENTER_ALWAYS }
      else if (injector.settings.tripod.EXIT_ALWAYS == input) { TripodStatus.EXIT_ALWAYS }
      else if (injector.settings.tripod.CLOSE == input) { TripodStatus.CLOSE }
      else if (injector.settings.tripod.BLOCK == input) { TripodStatus.BLOCK}
      else if (injector.settings.tripod.TWO_WAY == input) { TripodStatus.TWO_WAY }
      else {
        logger.error(s"Invalid tag${tag}")
        throw new Exception(s"Invalid tag${tag}")
      }
    }.getOrElse(TripodStatus.EXIT)
  }

  private def closeDoor() = {

  }

}
