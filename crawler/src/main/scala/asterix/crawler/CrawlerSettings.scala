package asterix
package crawler

import scala.concurrent.duration._

import akka.actor.{ActorSystem, Extension, ExtensionId, ExtensionIdProvider, ExtendedActorSystem}
import akka.util.Timeout

import com.typesafe.config.{Config, ConfigFactory}

class CrawlerSettings(cfg: Config) extends Extension {

  final val config: Config = {
    val config = cfg.withFallback(ConfigFactory.defaultReference)
    config.checkValid(ConfigFactory.defaultReference, "crawler")
    config
  }

  val RETRY_LATER_LOG_FILE = config.getString("crawler.retry-later-log-file")
  val FAILED_LOG_FILE = config.getString("crawler.failed-log-file")
  val OUTPUT_FILE_PATH = config.getString("crawler.output-file-path")
}

object CrawlerSettings extends ExtensionId[CrawlerSettings] with ExtensionIdProvider {
  override def lookup = CrawlerSettings

  override def createExtension(system: ExtendedActorSystem) =
    new CrawlerSettings(system.settings.config)

  override def get(system: ActorSystem): CrawlerSettings = super.get(system)
}