package sevts.terminal.acl

class AclController {

  def init() = {
    subscribe()
  }

  def onConnectionLost() = {

  }

  def onConnectionRestored() = {

  }

  def dataScannedHook(): Either[LoaclCheck, RemoteCheck] = {

  }

}
