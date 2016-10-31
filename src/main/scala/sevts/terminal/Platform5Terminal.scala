package sevts.terminal

import akka.actor.ActorSystem
import sevts.terminal.config.Settings
import sevts.terminal.networking.AppServer
import sevts.terminal.platform5.BrowserRunner

object Platform5Terminal {
  implicit val actorSystem = ActorSystem("platform5-terminal")

  val config = new Settings()

  def main( args: Array[String] ): Unit = {
    val serverBinding = AppServer(config)
  }
}
