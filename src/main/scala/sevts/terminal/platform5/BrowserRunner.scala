package sevts.terminal.platform5

import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.config.Settings

import scala.util.Try

object BrowserRunner {

  def apply(settings: Settings) = new BrowserRunner(settings)

}

class BrowserRunner(settings: Settings) extends LazyLogging {

  def runChrome() = {
    logger.info("Starting Сhrome browser")
    Try {
      val url = s"http://${settings.serverHost}:${settings.serverPort}"
      val params = s"/#/autologin?login=${settings.autoLoginConfig.username}" +
        s"&password=${settings.autoLoginConfig.password}" +
        s"&terminal=${settings.autoLoginConfig.terminal}"
      val fs = if(settings.chromeFullScreen) settings.autoLoginConfig.browserParams else ""
      val command = s"""start chrome $fs "$url$params""""
      logger.info(s"command line: $command")
      Runtime.getRuntime.exec(Array[String]("cmd", "/c", command))
    }.recover {
      case e: Exception ⇒
        logger.error("Unable to open Chrome browser")
        logger.error(e.getMessage, e)
    }

  }
}
