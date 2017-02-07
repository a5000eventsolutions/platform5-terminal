package sevts.terminal.config

import java.awt.print.PageFormat
import java.util.Map.Entry

import com.typesafe.config._
import com.typesafe.scalalogging.LazyLogging
import sevts.remote.protocol.Reaction
import sevts.terminal.config.Settings._

import collection.JavaConverters._

object Settings {

  sealed trait DeviceDriverType
  object DeviceDriverType {
    case object SerialPort extends DeviceDriverType
    case object Emulator extends DeviceDriverType
    case object RRU9809 extends DeviceDriverType
    case object Omnikey extends DeviceDriverType

    def apply(value: String): DeviceDriverType = {
      value.toLowerCase match {
        case "serialport" ⇒ DeviceDriverType.SerialPort
        case "emulator"   ⇒ DeviceDriverType.Emulator
        case "rfid9809"   ⇒ DeviceDriverType.RRU9809
        case "omnikey"    ⇒ DeviceDriverType.Omnikey
        case e: String ⇒ throw new IllegalStateException(s"Unknown device driver type $e")
      }
    }
  }

  sealed trait ReactionType
  object ReactionType {
    case object Redirect extends ReactionType
    case object Print extends ReactionType
    case object CheckBadge extends ReactionType
    case object AssignBarcode extends ReactionType
    case object OpenAndAssignBarcode extends ReactionType

    def apply(value: String): ReactionType = {
      value.toLowerCase match {
        case "redirect" ⇒ ReactionType.Redirect
        case "print" ⇒ ReactionType.Print
        case "check_badge" ⇒ ReactionType.CheckBadge
        case "assign_barcode" ⇒ ReactionType.AssignBarcode
        case "open_and_assign" ⇒ ReactionType.OpenAndAssignBarcode
        case e: String ⇒ throw new IllegalStateException(s"Unknown reaction type `$e`")
      }
    }
  }

  sealed trait FormatType
  object FormatType {
    case object Plain extends FormatType

    def apply(value: String): FormatType = {
      value.toLowerCase match {
        case "plain" ⇒ FormatType.Plain
        case e: String ⇒ throw new IllegalStateException(s"Unknown format type `$e`")
      }
    }
  }

  def apply(config: Config = ConfigFactory.load()) = new Settings(config)

  case class ReactionConfig(name: String, tpe: ReactionType, parameters: Config) {
    def toReaction = {
      tpe match {
        case ReactionType.Print ⇒
          Reaction.PrintBadge(parameters.getString("badgeTypeId"))
        case ReactionType.Redirect ⇒
          Reaction.OpenFormData
        case ReactionType.CheckBadge ⇒
          Reaction.CheckBadgeAccess
        case ReactionType.AssignBarcode ⇒
          Reaction.AssignBarcodeValue
        case ReactionType.OpenAndAssignBarcode ⇒
          Reaction.OpenAndAssign

      }
    }
  }


  object ReactionConfig {
    def apply(config: Config): ReactionConfig = {
      ReactionConfig(config.getString("name"),
        ReactionType(config.getString("type")),
        config.getConfig("parameters"))
    }
  }

  case class ScannerConfig(name: String, device: DeviceConfig, reaction: ReactionConfig,
                           format: FormatConfig,
                           parameters: Config)
  object ScannerConfig {
    def apply(config: Config, rootConfig: Settings): Option[ScannerConfig] = {
      for {
        device ← rootConfig.findDevice(config.getString("device"))
        reaction ← rootConfig.findReaction(config.getString("reaction"))
        format ← rootConfig.findFormat(config.getString("format"))
      } yield {
        ScannerConfig(
          config.getString("name"),
          device, reaction, format,
          config.getConfig("parameters")
        )
      }
    }
  }

  case class FormatConfig(name: String, driverType: FormatType, parameters: Config)
  object FormatConfig {
    def apply(config: Config): FormatConfig = {
      FormatConfig(
        config.getString("name"),
        FormatType(config.getString("driverType")),
        config.getConfig("parameters")
      )
    }
  }

  case class DeviceConfig(name: String, enabled: Boolean, deviceDriverType: DeviceDriverType,
                          parameters: Config)

  object DeviceConfig {
    def apply(config: Config): DeviceConfig = {
      DeviceConfig(config.getString("name"),
        config.getBoolean("enabled"),
        DeviceDriverType(config.getString("driverType")),
        config.getConfig("parameters"))
    }
  }

  object TerminalConfig {

    case class Devices(config: Config, settings: Settings) {
      val devices = config.getConfigList("devices").asScala.map(c ⇒ DeviceConfig(c))
      val formats = config.getConfigList("formats").asScala.map(f ⇒ FormatConfig(f))
      val reactions = config.getConfigList("reactions").asScala.map(r ⇒ ReactionConfig(r))
      val scanners = config.getConfigList("scanners").asScala.flatMap(s ⇒ ScannerConfig(s, settings))
    }



    object AutoLogin {
      def apply(config: Config): AutoLogin = {
        AutoLogin(config.getBoolean("enabled"), config.getString("username"),
          config.getString("password"), config.getString("terminal"))
      }
    }

    case class AutoLogin(enabled: Boolean, username: String, password: String,
                         terminal: String)
  }

  object PrinterConfig {

    def apply(config: Config) = {
      PrinterConfig(
        enabled = config.getBoolean("enabled"),
        page = PageConfig(config.getConfig("page")),
        devices = Devices(config.getConfig("devices"))
      )
    }

    case class PageConfig(config: Config) {
      private val cfgOrientation = config.getString("orientation")
      val orientation = if(cfgOrientation == "portrait") PageFormat.PORTRAIT else PageFormat.LANDSCAPE
    }

    case class Devices(config: Config) {
      val list = config.entrySet().asScala.map { (entry: Entry[String, ConfigValue]) ⇒
        entry.getKey → entry.getValue.unwrapped().asInstanceOf[String]
      }.toMap
    }
    case class PrinterConfig(enabled: Boolean, page: PageConfig, devices: Devices)
  }



  object RemoteServer {

    case class Address(system: String, host: String, port: Int) {
      override def toString() = s"akka://$system@$host:$port"
      def path = toString()
    }

    def apply(config: Config): Address = {
      Address(config.getString("system"), config.getString("host"), config.getInt("port"))
    }
  }

}

class Settings( config: Config = ConfigFactory.load() ) extends LazyLogging {

  val printing = PrinterConfig(config.getConfig("platform5.printing"))

  val remoteEnabled = config.getBoolean("platform5.server.remote.enabled")
  val remoteServer = RemoteServer(config.getConfig("platform5.server.remote"))

  val accessControlEnabled = config.getBoolean("platform5.terminal.accessControlEnabled")

  def findFormat(formatName: String) =
    config.getConfigList("platform5.terminal.config.formats").asScala
      .find(_.getString("name") == formatName )
      .map(r ⇒ FormatConfig(r))


  def findReaction(reactionName: String) =
    config.getConfigList("platform5.terminal.config.reactions").asScala
      .find(_.getString("name") == reactionName )
      .map(r ⇒ ReactionConfig(r))

  def findDevice(deviceName: String) =
    config.getConfigList("platform5.terminal.config.devices").asScala
      .find(_.getString("name") == deviceName )
      .map(r ⇒ DeviceConfig(r))

  val autoLoginConfig = TerminalConfig.AutoLogin(config.getConfig("platform5.terminal.autoLogin"))
  val terminalConfig = TerminalConfig.Devices(config.getConfig("platform5.terminal.config"), this)

  val serverHost = config.getString("platform5.server.remote.host")
  val serverPort = config.getString("platform5.server.remote.httpPort")
  val chromeFullScreen = config.getBoolean("platform5.chromeFullscreen")
}