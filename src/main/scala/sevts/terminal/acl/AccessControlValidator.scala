package sevts.terminal.acl

import zio.Task

trait AccessControlValidator {

  def validate(code: String): Task[Boolean]

}
