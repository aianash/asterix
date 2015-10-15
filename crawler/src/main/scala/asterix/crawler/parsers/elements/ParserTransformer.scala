package asterix
package crawler
package parsers
package elements

import scalaz._, Scalaz._

import play.api.libs.json._

import pages.WebPageTypes._


private[elements] object ParserTransformer {
  def unapply(tr: String) = none[JsValue => JsValue]
}