package sevts.terminal.actors.format

import sevts.terminal.actors.format.FormatsActor.Processed

object FormatActor {

  sealed trait Request
  object Request {
    case class Process(value: String) extends Request
  }

  sealed trait Response
  object Response {
    case class Result(formatted: Processed) extends Response
  }

}
