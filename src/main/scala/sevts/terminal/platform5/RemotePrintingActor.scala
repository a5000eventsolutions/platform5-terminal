package sevts.terminal.platform5

import java.awt.print.{Book, PageFormat, Paper, PrinterJob}
import java.io.{ByteArrayInputStream, File}
import java.nio.file.{Files, Paths}
import javax.print.{PrintService, PrintServiceLookup}

import akka.actor._
import com.typesafe.scalalogging.LazyLogging
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.printing.PDFPrintable
import sevts.server.domain.{DocumentRecord, Id, ME, Terminal}
import sevts.terminal.config.Settings

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal
import akka.pattern._
import akka.util.Timeout
import sevts.server.protocol.printing.Protocol._

object RemotePrintingActor {

  def props(settings: Settings): Props = Props(classOf[RemotePrintingActor], settings)

  private case object Warmup
  private case object Reconnect
}

class RemotePrintingActor(settings: Settings) extends Actor with LazyLogging {

  import RemotePrintingActor._

  implicit val ec = context.dispatcher
  implicit val timeout = Timeout(20 seconds)

  if(settings.printingEnabled && settings.remoteEnabled) {
    context.setReceiveTimeout(3 seconds)
  }

  val path = s"${settings.remoteServer.path}/user/printing-registry-actor"

  override def preStart() = {
    initPrinters()
  }

  def initPrinters() = {
    val printServices = PrintServiceLookup.lookupPrintServices(null, null)
    logger.info("==============================")
    logger.info("     System printers list    ")
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
     // val terminalId = Id[Terminal](settings.autoLoginConfig.terminal)
      actor ! RegisterTerminal(settings.autoLoginConfig.terminal)

    case TerminalRegistered ⇒
      logger.info("Print terminal registered on remote server")

    case p@PrintFile(terminal, badge, data) ⇒
      logger.info("Print file command received")
      (for {
        deviceContextOpt ← resolvePrinterService(settings.printerDevice)
        if deviceContextOpt.nonEmpty
        result ← doPrint(badge, data, deviceContextOpt.get)
      } yield {
        logger.info(s"Print task completed ${result.getJobName}")
        Enqueued
      }) recover {
        case e: Exception if NonFatal(e) ⇒
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

  }

  private def resolvePrinterService(printer: String): Future[Option[PrintService]] = Future {
    PrinterJob.lookupPrintServices().find( _.getName == printer )
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
    pageFormat.setOrientation(settings.printer.orientation)
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
