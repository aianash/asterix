package asterix
package crawler
package parsers
package elements

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import play.api.libs.json._

import pages._, WebPageTypes._

import scalaz._, Scalaz._

import com.typesafe.config._


object ElementParser {
  import WebPage._

  // create parser for
  def unapply(config: Any): Option[Parser[JsValue]] = config match {
    case StringParser(parser)   => parser.some
    case SelectorParser(parser) => parser.some
    case CombineParser(parser)  => parser.some
    case _                      => None
  }

}