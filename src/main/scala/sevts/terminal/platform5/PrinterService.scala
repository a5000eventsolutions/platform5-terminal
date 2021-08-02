package sevts.terminal.platform5

import java.awt.print.{Book, PageFormat, Paper, PrinterJob}
import java.io.ByteArrayInputStream

import javax.print.{PrintService, PrintServiceLookup}
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.printing.{PDFPrintable, Scaling}
import sevts.remote.protocol.Protocol.{PrintError, RemotePrintFile}
import sevts.server.documents.DocumentRecord
import sevts.server.domain.{FailureType, FileMeta, ME}
import sevts.terminal.Injector

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.control.NonFatal


class PrinterService(injector: Injector) extends LazyLogging {

  implicit val ec = injector.ec

  implicit val timeout = Timeout(15 seconds)

  val settings = injector.settings

  initPrinters()


  def print(command: RemotePrintFile) = {
    (for {
      _ <- Future.successful(logger.info("Resolve printer service"))
      deviceContextOpt <- resolvePrinterService(command.printer.id)
      if deviceContextOpt.nonEmpty
      result <- doPrint(command.fileMeta, command.file, deviceContextOpt.get)
    } yield {
      logger.info(s"Print task completed ${result.getJobName}")
      sevts.remote.protocol.Protocol.Enqueued(command.id, result.getJobName)
    }) recover {
      case NonFatal(e) =>
        logger.error(e.getMessage, e)
        PrintError(command.id, e)
    }
  }

  private def initPrinters() = {
    Thread.sleep(100)
    val printServices = PrintServiceLookup.lookupPrintServices(null, null)
    logger.info("==============================")
    logger.info("     System printers list     ")
    logger.info("==============================")
    printServices foreach { service =>
      logger.info(service.getName)
    }
    logger.info("==============================")

  }

  private def resolvePrinterService(printerId: String): Future[Option[PrintService]] = Future {
    val printerName = settings.printing.devices.list.getOrElse(printerId, throw FailureType.RecordNotFound)
    PrinterJob.lookupPrintServices().find( _.getName == printerName)
  }


  private def doPrint(fileMeta: ME[FileMeta], data: Array[Byte], printerService: PrintService): Future[PrinterJob] = Future {

    logger.info("Set paper params")

    val fileStream = new ByteArrayInputStream(data)
    val document = PDDocument.load(fileStream, MemoryUsageSetting.setupTempFileOnly())
    fileStream.close()

    val printerJob: PrinterJob = PrinterJob.getPrinterJob
    printerJob.setPrintService(printerService)
    printerJob.setJobName(s"${fileMeta.entity.originalName}-${scala.util.Random.nextInt(10000)}")

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
    val printable = new PDFPrintable(document, Scaling.ACTUAL_SIZE, false, injector.settings.printing.dpi)
    printable.setSubsamplingAllowed(true)
    book.append(printable, pageFormat, document.getNumberOfPages)
    printerJob.setPageable(book)
    logger.info("Print job started..")
    printerJob.print()
    document.close()
    logger.info("File printing job complete")
    printerJob
  }
}
