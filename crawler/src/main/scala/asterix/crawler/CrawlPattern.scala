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


case class CrawlPattern(val url: URL, config: Config) {
  import WebPage._
  import WebPageTypes.Parser

  val detailPgElementsConfig = config.getAnyRefList("item-page").asScala
  val itemDetailPageP: Parser[JsObject] =
    sequence(
      detailPgElementsConfig.flatMap {
        case ElementParser(parser) => parser.as[JsObject].some
        case _ => None
      }
    ) map (_.reduceOption(_ deepMerge _) getOrElse Json.obj())

  val entriesSelector = config.getString("entries-selector")

  val injectConfig = config.getAnyRefList("inject-to-items").asScala
  val injectP: Parser[JsObject] =
    sequence(
      injectConfig.flatMap {
        case ElementParser(parser) => parser.as[JsObject].some
        case _ => None
      }
    ) map (_.reduceOption(_ deepMerge _) getOrElse Json.obj())

  val detailsP: Parser[List[JsObject]] =
    select(entriesSelector)(attr("abs:href"))
      .flatMap { links =>
        sequence(
          links.map { url =>
            println(s"parsing ${url}")
            (Jsoup.connect(url).timeout(5000).get +> itemDetailPageP)
              .map(_ + ("url" -> JsString(url)))
          }
        )
      }

  val parser: Parser[List[JsObject]] =
    for {
      inject  <- injectP
      details <- detailsP
    } yield details.map(_ deepMerge inject)

}