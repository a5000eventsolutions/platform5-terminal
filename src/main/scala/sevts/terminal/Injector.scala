package sevts.terminal

import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import com.twitter.chill.{KryoInstantiator, KryoPool}
import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.actors.format.FormatsActor
import sevts.terminal.actors.readers.ReadersActor
import sevts.terminal.actors.scanners.ScannersActor
import sevts.terminal.actors.tripod.TripodControlActor
import sevts.terminal.actors.usbrelay.UsbRelayControlActor
import sevts.terminal.config.Settings
import sevts.terminal.platform5.RemoteTransportActor

object Injector {

}

trait Injector extends LazyLogging {

  implicit val system: ActorSystem
  implicit val ec = system.dispatcher

  def settings: Settings

  val serialization = SerializationExtension(system)

  val POOL_SIZE = 10
  val kryo: KryoPool = KryoPool.withByteArrayOutputStream(POOL_SIZE, new KryoInstantiator)


  val readersActor = system.actorOf(ReadersActor.props(settings, this), name = "readers-actor")
  val formatsActor = system.actorOf(FormatsActor.props(settings), name = "formats-actor")
  val scannersActor = system.actorOf(ScannersActor.props(this), name = "scanners-actor")

  val tripodActor = if(settings.tripod.enabled) {
    system.actorOf(TripodControlActor.props(this), name = "tripod-actor")
  }

  val usbRelayActor = if(settings.usbRelay.enabled) {
    system.actorOf(UsbRelayControlActor.props(this), name = "usbrelay-actor")
  }

  val endpointActor = system.actorOf(RemoteTransportActor.props(this), name = "remote-transport-actor")
}
