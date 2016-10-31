package sevts.terminal.actors.readers

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Props}
import sevts.terminal.actors.readers.SerialPortReader.Command.DataReceived
import sevts.terminal.config.Settings.DeviceConfig

import scala.concurrent.duration._

object EmulatorReaderActor {

  def props(listener: ActorRef, config: DeviceConfig): Props = {
    Props(classOf[EmulatorReaderActor], listener, config)
  }

}

class EmulatorReaderActor(listener: ActorRef, config: DeviceConfig) extends Actor {

  val delay = FiniteDuration(config.parameters.getDuration("delay").toMillis, TimeUnit.MILLISECONDS)

  implicit val ec = context.dispatcher

  override def preStart(): Unit = {
    context.system.scheduler.schedule(delay, delay,
      listener, ReadersActor.DeviceEvent.DataReceived(config.name,
        config.parameters.getString("data")))
  }

  override def receive: Receive = {
    case _ ⇒
  }
}
