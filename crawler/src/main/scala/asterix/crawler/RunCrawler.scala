package asterix
package crawler

import java.net.URL

import org.rogach.scallop._

import com.typesafe.config._

import akka.actor.{ActorSystem, Props}
import akka.pattern.pipe

import goshoplane.commons.core.services.UUIDGenerator


object RunCrawler {

  //
  class Conf(args: Seq[String]) extends ScallopConf(args) {
    val domain = opt[String]("domain", descr = "name of the website domain (used to find seed file)", required = true)
    val category = opt[String]("category", descr = "category of clothing item (used to find seed file)", required = true)
    val delay = opt[Long]("delay", descr = "max delay between pages (in millisecs)", default = Option(3000L))
  }

  //
  def main(args: Array[String]) {
    val cmd = new Conf(args)
    val domain = cmd.domain()
    val category = cmd.category()

    val seedf = s"seeds/$domain-$category-seeds.txt"

    val system = ActorSystem("asterix")
    val uuid = system.actorOf(UUIDGenerator.props(1L, 1L), "uuid")
    UUID.uuid(uuid)
    val crawler = system.actorOf(Crawler.props(), "crawler")

    val config =
      ConfigFactory.load("crawler")
                   .getConfig("crawler")

    implicit val ec = system.dispatcher
    val seeds = SeedURLUtils.get(seedf)
    seeds.foreach { urlstr =>
      val url = new URL(urlstr)
      val pattern = ItemListPageCrawlPattern(url.getHost, config.getConfig(url.getHost.replace(".", "-")))
      val jobF = UUID.id("job") map (idO => ExtractItemURLs(idO.get, new URL(urlstr), pattern, 1, false))
      jobF.map(Push(_)) pipeTo crawler
    }

    crawler ! Schedule
  }

}

object SeedURLUtils {

  private val StringListPattern = """\[\{(.*)\}\]""".r
  private val RangePattern = """\<\<([0-9].*)\>\>""".r

  //
  private def mkRange(rangestr: String) = {
    val comps = rangestr.split(":").map(_.toInt)
    assert(comps.size >= 2)
    if(comps.size == 3) comps(0) to comps(2) by comps(1)
    else comps(0) to comps(1)
  }

  //
  private def parse(link: String): Array[String] = {
    val seeds =
      for(baseLinks <- (StringListPattern findFirstIn link) map { list =>
                          for(cand <- list.substring(2, list.length - 2).split(",")) yield StringListPattern replaceAllIn(link, cand)
                      } orElse Some(Array(link))
      ) yield baseLinks.flatMap { base =>
          (RangePattern findFirstIn base) map { p =>
            val range = mkRange(p.substring(2, p.length - 2))
            for(i <- range) yield RangePattern replaceAllIn(base, i.toString)
          } getOrElse Seq.empty[String]
        }

    seeds getOrElse Array.empty[String]
  }

  //
  def get(filename: String) =
    scala.io.Source.fromFile(filename).getLines().flatMap(parse(_))

}