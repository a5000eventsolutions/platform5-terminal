package sevts.terminal

import akka.actor.ActorSystem
import org.slf4j.LoggerFactory
import sevts.terminal.config.Settings
import sevts.terminal.networking.AppServer
import sevts.terminal.service.BrowserRunner
import sevts.terminal.tripod.TripodController

object Platform5Terminal {
  implicit val actorSystem = ActorSystem("platform5-terminal")

  val config = new Settings()

  def main( args: Array[String] ): Unit = {
    val test = LoggerFactory.getLogger(classOf[TripodController].getName)
    test.info("Init")
    val serverBinding = AppServer(config)
  }
}
