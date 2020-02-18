package sevts.terminal.platform5

import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.config.Settings
import sevts.terminal.config.Settings.TerminalConfig.BrowserMonitor

import scala.util.Try
import scala.util.control.NonFatal

object BrowserRunner {

  def apply(settings: Settings) = new BrowserRunner(settings)

}

class BrowserRunner(settings: Settings) extends LazyLogging {

  def runMonitorWindows() = {
    val cfg = settings.autoLoginConfig
    if(cfg.enabled) {
      logger.info("Starting terminal monitors")
      cfg.monitors.foreach(runMonitor)
    } else {
      logger.info("Terminal autologin disabled by config")
    }

  }

  private def runMonitor(cfg: BrowserMonitor) = {
    logger.info("Starting Сhrome browser")

    Try {
      val url = s"http://${settings.serverHost}:${settings.serverPort}"
      val loginParams = s"/#/autologin?login=${settings.autoLoginConfig.username}" +
        s"&password=${settings.autoLoginConfig.password}" +
        s"&terminal=${settings.autoLoginConfig.terminal}" +
        s"&monitor=${cfg.name}"
      val terminalUrl = s"""--app=$url$loginParams"""

      val screen = s" --window-position=${cfg.position},0"
      val kiosk = s""" --kiosk --unsafely-treat-insecure-origin-as-secure="$url" --user-data-dir="./chrome${cfg.name}" """
      val chromeParams = s""" --unsafely-treat-insecure-origin-as-secure="$url" --user-data-dir="./chrome${cfg.name}" """

      val command = s"""start chrome $chromeParams "$terminalUrl""""

      logger.info(s"command line: $command")
      Runtime.getRuntime.exec(Array[String]("cmd", "/c", command))
    }.recover {
      case NonFatal(e) =>
        logger.error("Unable to open Chrome browser")
        logger.error(e.getMessage, e)
    }

  }
}
