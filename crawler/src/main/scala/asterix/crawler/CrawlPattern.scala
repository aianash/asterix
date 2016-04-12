package asterix
package crawler

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import java.net.URL

import com.typesafe.config._

import play.api.libs.json._

import parsers._, elements.ElementParser, pages.{WebPage, WebPageTypes}

import scalaz._, Scalaz._

import org.jsoup._

trait CrawlPattern[T] {
  import WebPageTypes.Parser

  def host: String
  def config: Config
  def parser: Parser[T]
}

//
case class ItemListPageCrawlPattern(host: String, config: Config) extends CrawlPattern[(List[String], JsObject)] {
  import WebPage._
  import WebPageTypes.Parser

  val injectConfig = config.getAnyRefList("inject-to-items").asScala
  val injectP: Parser[JsObject] =
    sequence(
      injectConfig.flatMap {
        case ElementParser(parser) => parser.as[JsObject].some
        case _ => None
      }
    ) map (_.reduceOption(_ deepMerge _) getOrElse Json.obj())


  private val entriesSelector = config.getString("entries-selector")

  val parser: Parser[(List[String], JsObject)] =
    for {
      inject <- injectP
      urls   <- select(entriesSelector)(attr("abs:href"))
    } yield urls -> inject
}

//
case class DetailPageCrawlPattern(host: String, config: Config, inject: JsObject) extends CrawlPattern[JsObject] {
  import WebPage._
  import WebPageTypes.Parser

  val detailPgElementsConfig = config.getAnyRefList("item-page").asScala
  val parser: Parser[JsObject] =
    sequence(
      detailPgElementsConfig.flatMap {
        case ElementParser(parser) => parser.as[JsObject].some
        case _ => None
      }
    ) map (_.reduceOption(_ deepMerge _) getOrElse Json.obj()) map (_ deepMerge inject)
}