package sevts.terminal

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.actors.format.FormatsActor
import sevts.terminal.actors.readers.ReadersActor
import sevts.terminal.actors.scanners.ScannersActor
import sevts.terminal.config.Settings
import sevts.terminal.platform5.{RemoteAccessControlActor, RemotePrintingActor}

object RootActors {

}

trait RootActors extends LazyLogging {

  implicit val system: ActorSystem

  def settings: Settings

  val readersActor = system.actorOf(ReadersActor.props(settings, this), name = "readers-actor")
  val formatsActor = system.actorOf(FormatsActor.props(settings), name = "formats-actor")
  val scannersActor = system.actorOf(ScannersActor.props(settings, this), name = "scanners-actor")

  val remoteEndpointActor = system.actorOf(RemotePrintingActor.props(settings), name = "remote-printing-actor")
  val remoteAccessControlActor = system.actorOf(RemoteAccessControlActor.props(settings, this), name = "remote-access-control-actor")
}
