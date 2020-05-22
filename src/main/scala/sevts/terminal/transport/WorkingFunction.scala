package sevts.terminal.transport

import akka.actor.{ReceiveTimeout, Terminated}
import sevts.terminal.transport.RemoteTransportActor._
import akka.pattern._
import sevts.terminal.modules.readers.ReadersActor
import sevts.terminal.service.ScannersService
import sevts.remote.protocol.Protocol._
import scala.util.control.NonFatal


trait WorkingFunction { self: RemoteTransportActor =>

  def workingFunction: StateFunction = {

    case Event(p@RemotePrintFile(_, printer, badge, data), workData: Data.Working) =>
      logger.info("Print file command received")
      printerService.print(p) pipeTo sender()
      stay() using workData.copy(lastActivity = System.currentTimeMillis())

    case Event(dr: ReadersActor.DeviceEvent.DataReceived, workData: Data.Working) =>
      ScannersService.dataReceived(injector, workData.id, dr) map { resultOpt =>
        resultOpt.foreach { result =>
          workData.wsClient ! TerminalMessage(workData.uid, result)
        }
      } recover {
        case NonFatal(e) =>
          logger.error("Scanned data processing failed", e)
      }
      stay()

    case Event(ActivityCheck, Data.Working(_, uid, actor, lastActivity)) =>
      if(System.currentTimeMillis() - lastActivity > 10000) {
        logger.error(s"Working Inactive timeout! ${actor} Trying reconnect..")
        goto(State.Connecting) using Data.Reconnect(sendConnectRequest(), System.currentTimeMillis())
      } else {
        stay()
      }

    case Event(Terminated(actor), _) =>
      logger.error("Terminal worker is down")
      stay()

    case Event(Ping, data: Data.Working) =>
      data.wsClient ! Pong
      stay() using data.copy(lastActivity = System.currentTimeMillis())

    case Event(msg: ServerMessage, data: Data.Working) =>
      logger.info(s"Push from server: ${msg.msg.toString}")
      context.system.eventStream.publish(msg)
      stay() using data.copy(lastActivity = System.currentTimeMillis())

    case Event(unknown, data: Data.Working) =>
      logger.info(s"Unknown event received ${unknown.toString} at state Working")
      stay()

    case unknown =>
      logger.info(s"Unknown message ${unknown.toString}")
      stay()

  }

}
