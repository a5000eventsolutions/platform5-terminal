package sevts.terminal.transport

import akka.actor.{ReceiveTimeout, Terminated}
import sevts.terminal.networking.websocket.WsClient
import sevts.terminal.networking.websocket.WsClient.WSException
import sevts.terminal.transport.RemoteTransportActor._
import akka.pattern._

trait ConnectingFunction { self: RemoteTransportActor =>

  def connectingFunction: StateFunction = {

    case Event(WsClient.Connected, reconnect: Data.Reconnect) =>
      logger.info("Remote access served identified")
      context.watch(reconnect.wsClient)
      this.context.self ! Warmup
      goto(State.Registering) using Data.ConnectionEstablished(reconnect.wsClient, System.currentTimeMillis())

    case Event(WsClient.Disconnected, reconnect: Data.Reconnect) =>
      logger.error(s"Connection failed")
      stay()

    case Event(ReceiveTimeout, reconnect: Data.Reconnect) =>
      logger.error(s"ident receive timeout ${reconnect.wsClient}")
      stay() using Data.Reconnect(sendConnectRequest(), System.currentTimeMillis())

    case Event(Terminated(actor), _) =>
      logger.error("Terminated")
      stay()

    case Event(ActivityCheck, reconnect: Data.Reconnect) =>
      if(System.currentTimeMillis() - reconnect.lastActivity > 10000) {
        logger.error(s"Connecting Inactive timeout! ${reconnect.wsClient} Trying reconnect..")
        stay() using Data.Reconnect(sendConnectRequest(), System.currentTimeMillis())
      } else stay()

    case Event(ex: WSException, _) =>
      logger.info(s"Catch exception ${ex.e.getMessage}")
      stay()
  }

}
