package sevts.terminal.acl

import java.util.UUID
import zio.Task


trait DataObserver {

  def dataObserver

}

object DataObserver {

  trait Service {

    def subscribe: Task[UUID]

  }

}
