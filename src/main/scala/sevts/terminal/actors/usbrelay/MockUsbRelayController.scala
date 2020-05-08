package sevts.terminal.actors.usbrelay

import com.typesafe.scalalogging.LazyLogging

class MockUsbRelayController extends LazyLogging {

  def start() = {
    logger.info("Mock start relay")
  }

  def open(tag: String) = {
    logger.info(s"Mock open channel with tag ${tag}")
  }



}
