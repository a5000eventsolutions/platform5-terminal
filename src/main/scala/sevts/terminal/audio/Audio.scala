package sevts.terminal.audio

import sevts.terminal.audio.Audio.Sample

import javax.sound.sampled._

object Audio {
  final case class Sample(line: Clip) {
    def play(block: Boolean = false): Unit = {
      if (line.isRunning) line.stop()
      line.setFramePosition(0)
      line.start()

      if (block) {
        val latch = new java.util.concurrent.CountDownLatch(1)
        val listener = new LineListener {
          override def update(event: LineEvent): Unit =
            if (event.getType == LineEvent.Type.STOP) latch.countDown()
        }
        line.addLineListener(listener)
        try latch.await()
        finally line.removeLineListener(listener)
      }
    }

    def close(): Unit = try line.close() catch { case _: Throwable => () }
  }
}

class Audio {

  private val completeSample: Sample = loadSample("/sounds/complite.wav")
  private val errorSample:    Sample = loadSample("/sounds/error.wav")

  private def loadSample(resourcePath: String): Sample = {
    val url = getClass.getResource(resourcePath)
    require(url != null, s"Resource not found: $resourcePath")

    val audioIn = AudioSystem.getAudioInputStream(url)
    val line    = AudioSystem.getClip()
    try {
      line.open(audioIn)
      Sample(line)
    } finally {
      try audioIn.close() catch { case _: Throwable => () }
    }
  }

  def playComplete(block: Boolean = false) =
    completeSample.play(block)

  def playError(block: Boolean = false) =
    errorSample.play(block)

  def close() = {
    completeSample.close()
    errorSample.close()
  }
}
