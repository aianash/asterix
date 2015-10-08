package asterix
package crawler

import java.io.{BufferedWriter, FileWriter, PrintWriter, StringReader}

import play.api.libs.json._

import org.jsoup._

import parsers.pages.WebPage


class Crawler(outputPath: String, pattern: CrawlPattern) {
  import WebPage._

  val writer = new PrintWriter(new BufferedWriter(new FileWriter(outputPath, true)))
  val parser = pattern.parser
  val doc = Jsoup.connect(pattern.url.toString).timeout(5000).get()

  def start: Unit = {
    println("starting " + pattern.url)
    run(parser)(doc) match {
      case Right(data) => data.foreach { js => writer.println(Json.stringify(js)) }
      case _ =>
    }
    scala.sys.ShutdownHookThread(writer.close)
  }

}

object Crawler {
  def apply(outputPath: String, pattern: CrawlPattern) = new Crawler(outputPath, pattern)
}