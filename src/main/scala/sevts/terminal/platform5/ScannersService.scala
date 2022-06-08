package sevts.terminal.platform5

import com.typesafe.scalalogging.LazyLogging
import sevts.server.domain.{Id, Terminal}
import sevts.server.protocol.TerminalEvent
import sevts.server.protocol.TerminalEvent.{AssignBarcodeValue, CheckBadgeAccess, OpenFormData}
import sevts.server.remote.Message.ScannerMessage
import sevts.server.remote.{Message, Reaction}
import sevts.terminal.Injector
import sevts.terminal.actors.readers.ReadersActor
import sevts.terminal.actors.scanners.ScannersActor
import akka.pattern._
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}


object ScannersService extends LazyLogging {

  def dataReceived(injector: Injector,
                   terminalId: Id[Terminal],
                   data: ReadersActor.DeviceEvent.DataReceived)
                  (implicit ec: ExecutionContext, timeout: Timeout): Future[Seq[TerminalEvent]] = {
    injector.scannersActor ? ScannersActor.Request.DataReceived(data.deviceName, data.data) map {
      case ScannersActor.Response.DataProcessed(msg) =>
        val extId = injector.settings.autoLoginConfig.externalId
        convertEvent(terminalId, extId, msg)
    }
  }

  private def convertEvent(terminalId: Id[Terminal],
                           externalId: Option[String],
                           event: Message): Seq[TerminalEvent] = {
    event match {
      case ScannerMessage(reaction, _, value, _, badgeSearch, formList, tag) =>
        logger.info("Terminal push message")
        reaction match {
          case Reaction.OpenFormData =>
            logger.info(s"Open formdata received $value")
            Seq(OpenFormData(terminalId, value, badgeSearch.getOrElse(false), formList))
          case Reaction.CheckAccess =>
            logger.info(s"Check badge access $value")
            Seq(CheckBadgeAccess(terminalId, externalId, value, tag))
          case Reaction.AssignBarcodeValue =>
            logger.info(s"Assign barcode value: `$value`")
            Seq(AssignBarcodeValue(terminalId, value))
          case Reaction.OpenAndAssign =>
            logger.info(s"Search and Assign barcode value: `$value`")
            Seq(
              OpenFormData(terminalId, value, badgeSearch.getOrElse(false), formList),
              AssignBarcodeValue(terminalId, value)
            )
          case msg =>
            logger.error(s"Disallowed reaction: $msg")
            Seq()
        }
    }
  }
}
