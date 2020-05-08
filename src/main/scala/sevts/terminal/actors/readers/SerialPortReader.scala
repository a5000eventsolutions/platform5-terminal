package sevts.terminal.actors.readers

import akka.actor.{Actor, ActorRef, Props}
import com.fazecast.jSerialComm._
import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.config.Settings
import sevts.terminal.config.Settings.DeviceConfig

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try
import scala.util.control.NonFatal

object SerialPortReader {
  def props(listener: ActorRef, device: DeviceConfig) = Props(classOf[SerialPortReader], listener, device)

  sealed trait Command
  object Command {
    case class DataReceived(port: SerialPort) extends Command
    case class ListenPort(port: SerialPort) extends Command
    case class OpenPort(portName: String) extends Command
    case class ClosePort(portName: String) extends Command
    case class SubscribeAll(actor: ActorRef) extends Command
    case class UnSubscribeAll(actor: ActorRef) extends Command
    case object ListPorts extends Command
  }

  case class SerialDataReceived(portName: String, deviceName: String, data: String)

  sealed trait Response
  object Response {
    case class PortOpened(name: String) extends Response
    case class PortClosed(name: String) extends Response
    case object Subscribed extends Response
    case object UnSubscribed extends Response
    case class Error(msg: String) extends Response
  }

}

class SerialPortReader(listener: ActorRef, device: DeviceConfig) extends Actor with LazyLogging {
  import SerialPortReader._

  implicit val system = context.system
  implicit val ec = context.dispatcher

  var pendingPorts = Set.empty[String]

  override def preStart = {
    initPorts(SerialPort.getCommPorts.toIndexedSeq)
  }

  def initPorts(ports: Seq[SerialPort]) = {
    val configPort = device.parameters.getString("port")

    ports.find(sp => sp.getSystemPortName == configPort) match {
      case Some(port) =>
        logger.info(s"Port activated: ${port.getSystemPortName}")
        port.setBaudRate(device.parameters.getInt("speed"))
        port.openPort()
        self ! Command.ListenPort(port)
      case None =>
        logger.error(s"Port $configPort not found!")
    }
  }

  def receive = {
    case Command.ListenPort(port) =>
      port.addDataListener(new SerialPortDataListener() {
        override def getListeningEvents() = SerialPort.LISTENING_EVENT_DATA_AVAILABLE

        override def serialEvent(event: SerialPortEvent) = {
          event.getEventType match {
            case SerialPort.LISTENING_EVENT_DATA_AVAILABLE =>
              //waiting 50ms for full packet receive
              val portName = port.getSystemPortName
              if(!pendingPorts.contains(portName)) {
                pendingPorts = pendingPorts + portName
                system.scheduler.scheduleOnce(50 milliseconds, self,
                  Command.DataReceived(port))
              }
            case e => logger.error("Unhandled serial event")
          }
        }
      })

    case Command.DataReceived(port) =>
      val result = Try {
        val newData = new Array[Byte](port.bytesAvailable)
        val numRead = port.readBytes(newData, newData.length)
        val msg = new String(newData).replace("\n", "").replace("\r", "")
        logger.info(s"Read $numRead bytes, content = `$msg`")
        listener ! ReadersActor.DeviceEvent.DataReceived(device.name, msg)
        pendingPorts = pendingPorts - port.getSystemPortName
      } recover {
        case e: Throwable if NonFatal(e) =>
          logger.error(e.getMessage, e)
          logger.error(s"Serial port ${port.getSystemPortName} error!")
          logger.info("Try to reinit port")
          initPorts(SerialPort.getCommPorts.toIndexedSeq)
      }


    case Command.OpenPort(portName) =>
      val ports = SerialPort.getCommPorts.toIndexedSeq
      val result = ports find( port =>
        port.getSystemPortName == portName) map { port =>
          port.openPort()
          Response.PortOpened(portName)
      } getOrElse Response.Error(s"Port $portName not found")
      sender() ! result

    case Command.ClosePort(portName) =>
      val ports = SerialPort.getCommPorts.toIndexedSeq
      val result = ports find( port =>
        port.getSystemPortName == portName) map { port =>
        port.closePort()
        Response.PortClosed(portName)
      } getOrElse Response.Error(s"Port $portName not found")
      sender() ! result

    case msg =>
      logger.error("Unknown message received ${msg.toString}")
  }
}
