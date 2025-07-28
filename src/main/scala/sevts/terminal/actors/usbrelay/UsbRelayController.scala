package sevts.terminal.actors.usbrelay

import com.sun.jna.Native
import com.sun.jna.win32.W32APIOptions
import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.Injector
import sevts.terminal.usbrelay
import sevts.terminal.usbrelay.UsbRelayLibrary

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal


class UsbRelayController(injector: Injector) extends LazyLogging {

  sealed trait OpenedChannel
  case object EnterChannel extends OpenedChannel
  case object ExitChannel extends OpenedChannel

  implicit val ec = injector.ec
  val settings = injector.settings.usbRelay

  var hHandle: Long = _
  var usbRelayLib: UsbRelayLibrary = _

  var enterOpen: Boolean = false
  var exitOpen: Boolean = false

  private def setChannelState(channel: OpenedChannel, state: Boolean) = {
    channel match {
      case EnterChannel => enterOpen = state
      case ExitChannel => exitOpen = state
    }
  }

  def start() = {

    println(System.getProperty("user.dir"))


    System.setProperty("jna.library.path", injector.settings.usbRelay.dllPath)

    usbRelayLib = Native.load("usb_relay_device.dll", classOf[usbrelay.UsbRelayLibrary])

    val initResult = usbRelayLib.usb_relay_init()

    if(initResult != 0) {
      logger.error(s"Error initialize relay (code ${initResult}). Shutting down")
      System.exit(-1)
    }

    logger.info(s"Open device with serial: `${settings.relaySerial}`")
    hHandle = usbRelayLib.usb_relay_device_open_with_serial_number(settings.relaySerial, settings.relaySerial.size)

    if(Option(hHandle).isEmpty) {
      logger.error(s"Error opening device. Shutting down")
      System.exit(-1)
    }
    logger.info(s"USBRelay handler value: `${hHandle}`")

  }

  def open(tag: String) = this.synchronized {
    logger.info(s"Try open channel with tag: ${tag}")

    tag match {

      case settings.directionEnterTag =>
        if(!enterOpen) {
          openChannel(hHandle, settings.enterChannelNum, EnterChannel)
        } else {
          logger.error("Enter channel already opened. Skip command")
        }


      case settings.directionExitTag =>
        if(!exitOpen) {
          openChannel(hHandle, settings.exitChannelNum, ExitChannel)
        } else {
          logger.error("Enter channel already opened. Skip command")
        }
    }
  }

  private def openChannel(h: Long, index: Int, channel: OpenedChannel) = try {
    val result = usbRelayLib.usb_relay_device_open_one_relay_channel(h, index)
    result match {
      case 0 =>
        logger.info(s"Channel ${index} is opened")
        injector.system.scheduler.scheduleOnce(settings.closeTime){
          setChannelState(channel, false)
          closeChannel(h, index)
        }
        setChannelState(channel, true)
      case 1 =>
        logger.error(s"Error open channel ${index}")
        setChannelState(channel, false)
      case 2 =>
        logger.error(s"Channel ${index} is outnumber the number of the usb relay device")
        setChannelState(channel, false)
    }
  } catch {
    case NonFatal(e) =>
      logger.error("Unknown error on open relay")
      logger.error(e.getMessage, e)
      setChannelState(channel, false)
  }

  private def closeChannel(h: Long, index: Int) = this.synchronized {
    try {
      val result = usbRelayLib.usb_relay_device_close_one_relay_channel(h, index)
      result match {
        case 0 => logger.info(s"Channel ${index} is closed")
        case 1 => logger.error(s"Error close channel ${index}")
        case 2 => logger.error(s"Close channel ${index} is outnumber the number of the usb relay device")
      }
    } catch {
      case NonFatal(e) =>
        logger.error("Unknown error on close relay")
        logger.error(e.getMessage, e)
    }
  }

}
