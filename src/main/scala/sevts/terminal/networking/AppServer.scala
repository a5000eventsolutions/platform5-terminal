package sevts.terminal.networking

import akka.actor.SupervisorStrategy.Restart
import akka.actor._
import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.RootActors
import sevts.terminal.config.Settings
import sevts.terminal.platform5.BrowserRunner

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.language.postfixOps

trait AppServer {

  def start(): Unit

}

object AppServer extends LazyLogging {

  sealed trait ServerEvent
  object ServerEvent {
    case class ConnectionOpen(id: String, subscriber: ActorRef) extends ServerEvent
    case object ConnectionClosed extends ServerEvent
  }

  implicit val ec = ExecutionContext.global

  def apply(config: Settings)(implicit system: ActorSystem) = {
    system.actorOf(Props(classOf[StandardAppServer], config, system))
    BrowserRunner(config).runChrome()
  }

  private[AppServer] class StandardAppServer(val settings: Settings)(implicit val system: ActorSystem)
    extends AppServer with Actor with LazyLogging with RootActors {

    override val supervisorStrategy =
      OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
        case _: Exception                => Restart
      }

    override def start(): Unit = {
      logger.info("Starting platfrom5 terminal..")
    }

    start()


    def receive = {
      case _ ⇒
    }
  }

}