package asterix
package crawler
package parsers
package elements

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import scalaz._, Scalaz._

import play.api.libs.json._

import pages._, WebPageTypes._


private[elements] object CombineParser {
  import WebPage._

  def unapply(config: java.util.Map[String, Any]): Option[Parser[JsObject]] = {
    if(config.contains("combine")) {
      val keyOP =
        config.get("key") match {
          case ElementParser(parser) => parser.as[JsString].map(_.value).some
          case _ => none
        }

      val valueOP =
        config.get("value") match {
          case ElementParser(parser) => parser.some
          case _ => none
        }

      (for(kP <- keyOP; vP <- valueOP)
        yield map2(kP, vP)((k, v) => Json.obj(k -> v))) orElse (succeed(Json.obj()).some)
    } else none[Parser[JsObject]]
  }
}