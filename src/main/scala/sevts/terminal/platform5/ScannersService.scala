package sevts.terminal.platform5

import com.typesafe.scalalogging.LazyLogging
import sevts.server.domain.{Id, Terminal}
import sevts.server.protocol.TerminalEvent
import sevts.server.protocol.TerminalEvent.OpenFormData
import sevts.server.remote.Message.ScannerMessage
import sevts.server.remote.{Message, Reaction}
import sevts.terminal.Injector
import sevts.terminal.actors.readers.ReadersActor
import sevts.terminal.actors.scanners.ScannersActor
import akka.pattern._
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}


object ScannersService extends LazyLogging {

  def dataReceived(injector: Injector, terminalId: Id[Terminal], data: ReadersActor.DeviceEvent.DataReceived)
                  (implicit ec: ExecutionContext, timeout: Timeout): Future[Option[TerminalEvent]] = {
    injector.scannersActor ? ScannersActor.Request.DataReceived(data.deviceName, data.data) map {
      case ScannersActor.Response.DataProcessed(msg) ⇒
        convertEvent(terminalId, msg)
    }
  }

  private def convertEvent(terminalId: Id[Terminal], event: Message): Option[TerminalEvent] = {
    event match {
      case ScannerMessage(reaction, _, value, _, badgeSearch, formList) ⇒
        logger.info("Terminal push message")
        reaction match {
          case Reaction.OpenFormData ⇒
            logger.info(s"Open formdata received $value")
            Some(OpenFormData(terminalId, value, badgeSearch.getOrElse(false), formList))
          case Reaction.CheckAccess ⇒
            logger.info(s"Check badge access $value")
            Some(OpenFormData(terminalId, value, badgeSearch.getOrElse(false), formList))
          case msg ⇒
            logger.error(s"Disallowed reaction: $msg")
            None
        }
    }
  }
}
