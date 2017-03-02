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

  def props(settings: Settings): Props = Props(classOf[RemotePrintingActor], settings)

  private case object Warmup
  private case object Reconnect
}

class RemotePrintingActor(settings: Settings) extends Actor with LazyLogging {

  import RemotePrintingActor._

  implicit val ec = context.dispatcher
  implicit val timeout = Timeout(20 seconds)

  if(settings.printing.enabled && settings.remoteEnabled) {
    context.setReceiveTimeout(3 seconds)
  }

  val path = s"${settings.remoteServer.path}/user/root/printing-registry-actor"

  override def preStart() = {
    initPrinters()
  }

  def initPrinters() = {
    val printServices = PrintServiceLookup.lookupPrintServices(null, null)
    logger.info("==============================")
    logger.info("     System printers list     ")
    logger.info("==============================")
    printServices foreach { service ⇒
      logger.info(service.getName)
    }
    logger.info("==============================")

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
      (for {
        deviceContextOpt ← resolvePrinterService(printer.id)
        if deviceContextOpt.nonEmpty
        result ← doPrint(badge, data, deviceContextOpt.get)
      } yield {
        logger.info(s"Print task completed ${result.getJobName}")
        sevts.remote.protocol.Protocol.Enqueued(result.getJobName)
      }) recover {
        case NonFatal(e) ⇒
          logger.error(e.getMessage, e)
          PrintError(e)
      } pipeTo sender()

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

  private def resolvePrinterService(printerId: String): Future[Option[PrintService]] = Future {
    val printerName = settings.printing.devices.list.getOrElse(printerId, throw FailureType.RecordNotFound)
    PrinterJob.lookupPrintServices().find( _.getName == printerName)
  }


  private def doPrint(badge: ME[DocumentRecord], data: Array[Byte], printerService: PrintService): Future[PrinterJob] = Future {
    val document = PDDocument.load(data)

    val printerJob: PrinterJob = PrinterJob.getPrinterJob
    printerJob.setPrintService(printerService)
    printerJob.setJobName(s"${badge.id}-${scala.util.Random.nextInt(10000)}")

    val paper = new Paper()
    val mediaBox = document.getPage(0).getMediaBox
    paper.setSize(mediaBox.getWidth, mediaBox.getHeight)
    //paper.setSize(settings.printer.pageWidth, settings.printer.pageHeight)
    paper.setImageableArea(
      document.getPage(0).getBBox.getLowerLeftX,
      document.getPage(0).getBBox.getLowerLeftY,
      document.getPage(0).getBBox.getWidth,
      document.getPage(0).getBBox.getHeight
    )

    // custom page format
    val pageFormat = new PageFormat()
    pageFormat.setOrientation(settings.printing.page.orientation)
    pageFormat.setPaper(paper)

    // override the page format
    val book = new Book()
    // append all pages
    book.append(new PDFPrintable(document), pageFormat, document.getNumberOfPages)
    printerJob.setPageable(book)
    printerJob.print()
    document.close()
    logger.info("File printing job complete")
    printerJob
  }
}
