package sevts.terminal

import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.actors.format.FormatsActor
import sevts.terminal.actors.readers.ReadersActor
import sevts.terminal.actors.scanners.ScannersActor
import sevts.terminal.config.Settings
import sevts.terminal.platform5.RemoteTransportActor

object Injector {

}

trait Injector extends LazyLogging {

  implicit val system: ActorSystem
  implicit val ec = system.dispatcher

  def settings: Settings

  val serialization = SerializationExtension(system)

  val readersActor = system.actorOf(ReadersActor.props(settings, this), name = "readers-actor")
  val formatsActor = system.actorOf(FormatsActor.props(settings), name = "formats-actor")
  val scannersActor = system.actorOf(ScannersActor.props(this), name = "scanners-actor")

//  val printingEndpointActor = system.actorOf(RemotePrintingActor.props(settings), name = "remote-printing-actor")
//  val scannersEndpointActor = system.actorOf(RemoteScannersActor.props(this), name = "remote-scanners-actor")
  val endpointActor = system.actorOf(RemoteTransportActor.props(this), name = "remote-scanners-actor")
}
