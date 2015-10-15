package asterix
package crawler
package parsers
package elements

import scalaz._, Scalaz._

import play.api.libs.json._

import pages._, WebPageTypes._


private[elements] object StringParser {
  import WebPage._

  val attrR = """attr\((.*)\)""".r

  def unapply(str: String): Option[Parser[JsValue]] = str match {
    case "text"         => text.map(JsString(_)).some
    case "ownText"      => ownText.map(JsString(_)).some
    case attrR(attrKey) => attr(attrKey).map(JsString(_)).some
    case _              => none
  }
}