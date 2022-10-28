package sevts.terminal.platform5

import com.typesafe.scalalogging.LazyLogging

import java.io.{File, IOException, RandomAccessFile}
import java.net.ServerSocket
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

class SecondLaunchBlocker(enabled: Boolean) extends LazyLogging {

  def run(): ServerSocket = {
    if (enabled) {
      logger.info("Prevent second launch..")
      block()
    } else {
      logger.info("Second launch allowed.")
      null
    }
  }

  private def block(): ServerSocket = {
    import java.io.IOException
    try {
      val socket = new ServerSocket(34567)
      logger.info("Another instance not found. Starting continue.")
      logger.info(s"Create socket lock: ${socket.toString}")
      socket
    } catch {
      case ex: IOException =>
        logger.error("Another terminal instance is running. Shutdown current process")
        System.exit(0)
        throw new Exception("ss")
    }
  }
}
