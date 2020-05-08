package sevts.terminal.actors.readers

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Props}
import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.config.Settings.DeviceConfig

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.language.postfixOps


object EmulatorReaderActor {

  def props(listener: ActorRef, config: DeviceConfig): Props = {
    Props(classOf[EmulatorReaderActor], listener, config)
  }

}

class EmulatorReaderActor(listener: ActorRef, config: DeviceConfig) extends Actor with LazyLogging {

  case object Tick

  val delay = FiniteDuration(config.parameters.getDuration("delay").toMillis, TimeUnit.MILLISECONDS)

  implicit val ec = context.dispatcher

  val dataArray = config.parameters.getStringList("data").asScala.toVector

  var index = 0

  override def preStart(): Unit = {
    context.system.scheduler.scheduleAtFixedRate(20 seconds, delay, self, Tick)
  }

  override def receive: Receive = {
    case Tick =>
      listener ! ReadersActor.DeviceEvent.DataReceived(config.name, dataArray(index))
      index = index + 1
      if(index == dataArray.length) { index = 0 }
  }
}
