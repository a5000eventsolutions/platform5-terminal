package sevts.terminal.platform5

import java.awt.print.{Book, PageFormat, Paper, PrinterJob}
import javax.print.{PrintService, PrintServiceLookup}

import akka.actor._
import com.typesafe.scalalogging.LazyLogging
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.printing.PDFPrintable
import sevts.server.domain._
import sevts.terminal.config.Settings

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal
import akka.pattern._
import akka.util.Timeout
import sevts.remote.protocol.Protocol._


object RemotePrintingActor {

  //def props(settings: Settings): Props = Props(classOf[RemotePrintingActor], settings)
//
//  private case object Warmup
//  private case object Reconnect
}

class RemotePrintingActor(settings: Settings) {

  /*import RemotePrintingActor._

  implicit val ec = context.dispatcher
  implicit val timeout = Timeout(20 seconds)

  if(settings.printing.enabled && settings.remoteEnabled) {
    context.setReceiveTimeout(3 seconds)
  }


  override def preStart() = {
    initPrinters()
  }



  def sendIdentifyRequest() = context.actorSelection(path) ! Identify(path)

  def receive = identifying

  def identifying: Receive = {

    case ActorIdentity(`path`, Some(actor)) ⇒
      context.watch(actor)
      context.become(active(actor))
      context.setReceiveTimeout(Duration.Undefined)
      self ! Warmup

    case ActorIdentity(`path`, None) ⇒
      logger.error(s"Remote actor not available: $path")
      context.system.scheduler.scheduleOnce(15 seconds, self, Reconnect)

    case Reconnect ⇒
      sendIdentifyRequest()

    case ReceiveTimeout ⇒
      sendIdentifyRequest()
  }

  def active(actor: ActorRef): Receive = {

    case Warmup ⇒
      actor ! RegisterTerminal(settings.autoLoginConfig.terminal)

    case TerminalRegistered(id) ⇒
      logger.info(s"Print terminal id=`${id.value}` registered on remote server")

    case p@RemotePrintFile(_, printer, badge, data) ⇒
      logger.info("Print file command received")
      pipeTo sender()

    case Ping ⇒
      sender() ! Pong

    case Terminated(`actor`) ⇒
      logger.error("Server connection lost!!!")
      logger.error("Trying reconnect")
      context.become(identifying)
      sendIdentifyRequest()

    case AccessDenied ⇒
      logger.error(s"Access denied for terminal with name `${settings.autoLoginConfig.terminal}`")
      logger.error("Stopping terminal process..")
      context.system.terminate()

    case unknown ⇒
      logger.error(s"Unknown message ${unknown.toString}")

  }
*/

}
