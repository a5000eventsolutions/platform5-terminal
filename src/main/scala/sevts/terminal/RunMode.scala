package sevts.terminal

import com.typesafe.config.ConfigFactory
import sevts.terminal.config.Settings

object RunModeConfig {
  val configOverride = s"""
    {
      platform5 {
        testAuthEnabled = true
      }
    }
    """

  def handleArgs( settings: Settings, args: Array[String] ): Settings = {
    if (args.length != 0 && args(0) == "--test-auth") {
      val cfg = ConfigFactory.parseString(configOverride)
        .withFallback(ConfigFactory.load())
      new Settings(cfg)
    }
    settings
  }
}
