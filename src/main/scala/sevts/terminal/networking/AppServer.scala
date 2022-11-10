package sevts.terminal.networking

import akka.actor.SupervisorStrategy.Restart
import akka.actor._
import com.typesafe.scalalogging.LazyLogging
import sevts.terminal.Injector
import sevts.terminal.config.Settings
import sevts.terminal.platform5.{BrowserRunner, SecondLaunchBlocker}

import java.net.ServerSocket
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

  var socketLock: ServerSocket = null

  def apply(config: Settings)(implicit system: ActorSystem) = {

    socketLock = new SecondLaunchBlocker(config.preventSecondLaunch).run()
    if (!config.testAuthEnabled) {
      BrowserRunner(config).runMonitorWindows()
    }
    if(!config.frontOnly) {
      system.actorOf(Props(classOf[StandardAppServer], config, system))
    } else {
      logger.info("Front only mode..")
    }

  }

  private[AppServer] class StandardAppServer(val settings: Settings)(implicit val system: ActorSystem)
    extends AppServer with Actor with LazyLogging with Injector {

    override val supervisorStrategy =
      OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
        case _: Exception                => Restart
      }

    override def start(): Unit = {
      logger.info("Starting platfrom5 terminal..")
    }

    start()

    def receive = {
      case _ =>
    }
  }

}
