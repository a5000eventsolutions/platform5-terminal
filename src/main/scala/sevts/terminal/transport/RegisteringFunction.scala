package sevts.terminal.transport

import java.time.Instant

import akka.actor.{ReceiveTimeout, Terminated}
import sevts.terminal.networking.websocket.WsClient
import sevts.terminal.transport.RemoteTransportActor._
import akka.pattern._
import sevts.remote.protocol.Protocol.{AccessDenied, Ping, Pong, RegisterError, RegisterTerminal, TerminalRegistered}

import scala.concurrent.duration.Duration


trait RegisteringFunction { self: RemoteTransportActor =>

  def registeringFunction: StateFunction = {

    case Event(WsClient.Connected, _) =>
      logger.error("WTF?")
      context.setReceiveTimeout(Duration.Undefined)
      this.context.self ! Warmup
      stay()

    case Event(Warmup, data: Data.ConnectionEstablished) =>
      val terminalName = settings.autoLoginConfig.terminal
      val login = settings.autoLoginConfig.username
      val password = settings.autoLoginConfig.password
      logger.info(s"Register access control terminal ${data.wsClient}")
      data.wsClient ! RegisterTerminal(login, password, terminalName, settings.organisationId)
      stay() using data.copy(lastActivity = Instant.now().getEpochSecond)

    case Event(TerminalRegistered(id, uid), Data.ConnectionEstablished(a, l)) =>
      logger.info(s"Terminal registered on access control server as id `${id.value}`")
      goto(State.Working) using Data.Working(id, uid, a, l)

    case Event(AccessDenied, _) =>
      logger.error(s"Access denied for terminal with name `${settings.autoLoginConfig.terminal}`")
      logger.error("Stopping terminal process..")
      context.system.terminate()
      stop()

    case Event(RegisterError, _) =>
      logger.error("Terminal register error on access control server")
      stay()

    case Event(Ping, data: Data.ConnectionEstablished) =>
      logger.error("reg conn established")
      data.wsClient ! Pong
      stay() using data.copy(lastActivity = System.currentTimeMillis())

    case Event(ActivityCheck, data: Data.ConnectionEstablished) =>
      if(System.currentTimeMillis() - data.lastActivity > 10000) {
        logger.error(s"Registering Inactive timeout ${data.wsClient}! Trying reconnect..")
        goto(State.Connecting) using Data.Reconnect(sendConnectRequest(), System.currentTimeMillis())
      } else {
        stay()
      }

    case Event(Terminated(actor), _) =>
      logger.error(s"Terminated ${actor}. Registering reconnect")
      stay()

  }

}
