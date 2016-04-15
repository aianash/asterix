package asterix
package crawler

import play.api.libs.json._

sealed trait CrawlerProtocol
case class Push(job: Job) extends CrawlerProtocol
case object Schedule extends CrawlerProtocol
case class Completed(job: Job) extends CrawlerProtocol
case class Failed(job: Job) extends CrawlerProtocol
case class Append(jsons: List[JsObject]) extends CrawlerProtocol
case object ShowStats extends CrawlerProtocol