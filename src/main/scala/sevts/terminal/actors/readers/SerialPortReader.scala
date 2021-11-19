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
    case class ClosePort(portName: String) extends Command
    case object ListPorts extends Command
    case object Reconnect extends Command
    case class CheckPortStatus(port: SerialPort) extends Command
  }

  case class SerialDataReceived(portName: String, deviceName: String, data: String)
}

class SerialPortReader(listener: ActorRef, device: DeviceConfig) extends Actor with LazyLogging {
  import SerialPortReader._

  implicit val system = context.system
  implicit val ec = context.dispatcher

  var pending: Boolean = false
  val configPort = device.parameters.getString("port")

  override def preStart = {
    initPorts(SerialPort.getCommPorts.toIndexedSeq)
  }

  def initPorts(ports: Seq[SerialPort]) = {

    ports.find(sp => sp.getSystemPortName == configPort) match {
      case Some(port) =>
        logger.info(s"Port activated: ${port.getSystemPortName}")
        port.setBaudRate(device.parameters.getInt("speed"))
        port.openPort()
        self ! Command.ListenPort(port)
        context.system.scheduler.scheduleOnce(2 seconds, self, Command.CheckPortStatus(port))
      case None =>
        logger.error(s"Port $configPort not found! Try reconnecct after 2 seconds")
        context.system.scheduler.scheduleOnce(2 seconds, self, Command.Reconnect)
    }
  }

  def receive = {

    case Command.Reconnect =>
      initPorts(SerialPort.getCommPorts.toIndexedSeq)

    case Command.CheckPortStatus(port) =>
      SerialPort.getCommPorts.toIndexedSeq
        .find(sp => sp.getSystemPortName == configPort) match {
          case Some(_) =>
            context.system.scheduler.scheduleOnce(2 seconds, self, Command.CheckPortStatus(port))
          case None =>
            pending = false
            Try(port.closePort())
            logger.info(s"Serial port `${configPort}` is out. Try reconnect")
            self ! Command.Reconnect
        }

    case Command.ListenPort(port) =>
      port.addDataListener(new SerialPortDataListenerWithExceptions() {
        override def getListeningEvents() = SerialPort.LISTENING_EVENT_DATA_AVAILABLE

        override def serialEvent(event: SerialPortEvent) = {
          event.getEventType match {
            case SerialPort.LISTENING_EVENT_DATA_AVAILABLE =>
              //waiting 50ms for full packet receive
              if(!pending) {
                pending = true
                system.scheduler.scheduleOnce(50 milliseconds, self,
                  Command.DataReceived(port))
              }
            case e =>
              pending = false
              Try(port.closePort())
              logger.error("Unhandled serial event. Reconnect port")
              self ! Command.Reconnect
          }
        }

        override def catchException(e: Exception) = {
          pending = false
          logger.info(s"Exception serial listener oon `${port.getSystemPortName}`")
          self ! initPorts(SerialPort.getCommPorts.toIndexedSeq)
        }
      })

    case Command.DataReceived(port) =>
      Try {
        val newData = new Array[Byte](port.bytesAvailable)
        val numRead = port.readBytes(newData, newData.length)
        val msg = new String(newData).replace("\n", "").replace("\r", "")
        logger.info(s"Read $numRead bytes, content = `$msg`")
        listener ! ReadersActor.DeviceEvent.DataReceived(device.name, msg)
        pending = false
      } recover {
        case e: Throwable if NonFatal(e) =>
          pending = false
          Try(port.closePort())
          logger.error(e.getMessage, e)
          logger.error(s"Serial port ${port.getSystemPortName} error!")
          logger.info("Try to reinit port")
          initPorts(SerialPort.getCommPorts.toIndexedSeq)
      }

    case msg =>
      logger.error(s"Unknown message received ${msg.toString}")
  }
}
