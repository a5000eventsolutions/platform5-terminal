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
            case CheckAccessResult.Allowed ⇒ openDoor(data.monitors)
            case _ ⇒ closeDoor()
          }
        case _ ⇒
      }

    case unknown ⇒
      logger.error(s"Unknown tripod message ${unknown.toString}")

  }

  private def openDoor(monitors: Seq[Monitor]) = {
    val NUMBER_OF_ATTEMPTS = 3
    val direction = getMonitorDirection(monitors)
    logger.info(s"Open door direction: ${direction.name()}")
    controller.setDoorStatus(TripodStatus.getTripodStatusByDirectionType(direction), NUMBER_OF_ATTEMPTS)
  }

  private def getMonitorDirection(monitors: Seq[Monitor]): DirectionType = {
    monitors.headOption.map { monitor ⇒
      if(injector.settings.tripod.directionEnter == monitor.name) {
        DirectionType.ENTER
      } else { DirectionType.EXIT }
    }.getOrElse(DirectionType.EXIT)
  }

  private def closeDoor() = {

  }

}
