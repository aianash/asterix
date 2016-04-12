package asterix
package crawler

import scala.concurrent.{Future, ExecutionContext}

import java.net.URL
import java.io.File

import org.jsoup._

import play.api.libs.json._

import com.typesafe.config.Config

import parsers._, pages.{WebPage, WebPageTypes}

//
trait Job {
  def id: Long
  def isRetry: Boolean
  def execute()(implicit ec: ExecutionContext): Future[List[CrawlerProtocol]]
  def attempt: Int

  def copy(id: Long, attempt: Int): this.type
  def json: String = "ghfghf"
}

//
case class ExtractItemURLs(id: Long, url: URL, pattern: ItemListPageCrawlPattern, attempt: Int, isRetry: Boolean) extends Job {
  import WebPage._

  def execute()(implicit ec: ExecutionContext) = Future {
    val doc = Jsoup.connect(url.toString)
                   .userAgent("Mozilla/5.0 (X11; U; Linux i686; pl-PL; rv:1.9.0.2) Gecko/20121223 Ubuntu/9.25 (jaunty) Firefox/3.8")
                   .timeout(10000)
                   .get()

    run(pattern.parser)(doc) match {
      case Right((urls, inject)) =>
        val dtPgPattern = DetailPageCrawlPattern(pattern.host, pattern.config, inject)
        List(Push(DetailPage2Json(-1, urls, dtPgPattern, 1, isRetry)))
      case Left(pe) => List(Failed(this))
    }
  }

  def copy(id: Long, attempt: Int) = this.copy(id = id, attempt = attempt)
}

//
case class DetailPage2Json(id: Long, batch: List[String], pattern: DetailPageCrawlPattern, attempt: Int, isRetry: Boolean) extends Job {
  import WebPage._
  import WebPageTypes.Parser

  val parser: Parser[List[JsObject]] =
    succeed(batch) flatMap { links =>
      sequence(
        links.map { url =>
            println(s"\tParsing $url")
            (Jsoup.connect(url)
                  .userAgent("Mozilla/5.0 (X11; U; Linux i686; pl-PL; rv:1.9.0.2) Gecko/20121223 Ubuntu/9.25 (jaunty) Firefox/3.8")
                  .timeout(10000)
                  .get +> pattern.parser)
              .map(_ + ("url" -> JsString(url)))
        }
      )
    }

  def execute()(implicit ec: ExecutionContext) = Future {
    parser(null).extract match {
      case Right(jsons) => List(Append(jsons))
      case _ => List(Failed(this))
    }
  }

  def copy(id: Long, attempt: Int) = this.copy(id = id, attempt = attempt)
}

// //
// case class ImageJob(id: Int, batch: List[URL], outputdir: File, isRetry: Boolean) extends Job {
//   def execute = {}
// }
