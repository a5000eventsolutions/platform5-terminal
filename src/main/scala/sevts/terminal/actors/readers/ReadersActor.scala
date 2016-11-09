package sevts.terminal.actors.readers

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.actor.Actor.Receive
import akka.pattern._

import scala.concurrent.duration._
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import sevts.remote.protocol.TerminalMessage.TerminalEPCEvent
import sevts.terminal.Injector
import sevts.terminal.config.Settings
import sevts.terminal.config.Settings.{DeviceConfig, DeviceDriverType}

import scala.util.control.NonFatal

object ReadersActor {

  def props(settings: Settings, Injector: Injector): Props = {
    Props(classOf[ReadersActor], settings, Injector)
  }

  sealed trait DeviceEvent
  object DeviceEvent {
    case class DataReceived(deviceName: String, data: String) extends Request
    case class EPCReceived(deviceName: String, data: Array[Byte]) extends Request
    case class Stopped(deviceName: String) extends Request
  }

  sealed trait Request
  object Request {
    case object StopDevices extends Request
    case object StartDevices extends Request
    case class RegisterDevice(device: DeviceConfig) extends Request
    case class RegisterListener(actorRef: ActorRef) extends Request
    case class RemoveListener(actorRef: ActorRef) extends Request
  }

  sealed trait Response
  object Response {
    case object Registered extends Response
    case object Unregistered extends Response
  }

}

case class ReadersActor(settings: Settings, Injector: Injector) extends Actor with LazyLogging {
  import ReadersActor._

  private var listeners = Set[ActorRef]()
  private var deviceActors = Map[DeviceConfig, ActorRef]()

  implicit val ec = context.dispatcher
  implicit val timeout = Timeout(250.millis)

  override def preStart(): Unit = {
    self ! Request.StartDevices
  }

  override def aroundPostStop(): Unit = {
    self ! Request.StopDevices
  }

  override def receive: Receive = {

    case msg: Rfid9809ReaderActor.Commands.WriteEpcData ⇒
      deviceActors foreach { case (deviceConfig, deviceRef) ⇒
        deviceConfig.deviceDriverType match {
          case DeviceDriverType.RRU9809 ⇒
            deviceRef ! msg
          case any ⇒
        }
      }

    case msg: Rfid9809ReaderActor.Response ⇒
      val response = TerminalEPCEvent(msg.toString)
      //Injector.remoteAccessControlActor ! response

    case Request.StartDevices ⇒
      settings.terminalConfig.devices
        .filter(r ⇒ r.enabled )
        .foreach( r ⇒
          self ! Request.RegisterDevice(r)
        )

    case Request.StopDevices ⇒
      deviceActors foreach { case (deviceName, device) ⇒
        (device ? PoisonPill) map {
          case ReadersActor.DeviceEvent.Stopped(name) ⇒
            deviceActors = deviceActors.filter(_._1.name != deviceName)
          case _ ⇒
            logger.info(s"Stop failed for $deviceName")
        } recover {
          case e: Throwable if NonFatal(e) ⇒
            logger.info(s"Stop failed for $deviceName")
        }
      }

    case Request.RegisterDevice(device) ⇒
      device.deviceDriverType match {
        case DeviceDriverType.Emulator ⇒
          deviceActors += (device → context.actorOf(EmulatorReaderActor.props(self, device),
            name = device.name + "-emulator-reader-actor"))
        case DeviceDriverType.SerialPort ⇒
          deviceActors += (device → context.actorOf(SerialPortReader.props(self, device),
            name = device.name + "-serialport-reader-actor"))
        case DeviceDriverType.Omnikey ⇒
          deviceActors += (device → context.actorOf(OmnikeyReaderActor.props(self, device),
            name = device.name + "-omnikey-reader-actor"))
        case DeviceDriverType.RRU9809 ⇒
          deviceActors += (device → context.actorOf(Rfid9809ReaderActor.props(self, device),
            name = device.name + "-rru9809-reader-actor"))
        case unknown ⇒
          logger.error(s"Unknown device type ${unknown.toString}")
      }

    case d: DeviceEvent.DataReceived ⇒
      listeners foreach { listener ⇒
        listener ! d
      }

    case e: DeviceEvent.EPCReceived ⇒
      listeners foreach { listener ⇒
        listener ! e
      }

    case Request.RemoveListener(listener) ⇒
      listeners -= listener

    case Request.RegisterListener(listener) ⇒
      listeners += listener

    case unknown ⇒
      logger.info(unknown.toString)
  }
}
