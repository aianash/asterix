package asterix
package crawler

import org.rogach.scallop._

import com.typesafe.config._


object RunCrawler {

  class Conf(args: Seq[String]) extends ScallopConf(args) {
    val url = opt[String]("url", descr = "page with results to crawl", required = true)
    val output = opt[String]("output", descr = "file to append crawled result", required = true)
  }

  def main(args: Array[String]) {
    val cmd = new Conf(args)
    val url = new java.net.URL(cmd.url())
    val config =
      ConfigFactory.load("crawler")
                   .getConfig("crawler")
                   .getConfig(url.getHost.replace(".", "-"))
    val output = cmd.output()
    val pattern = CrawlPattern(url, config)
    Crawler(output, pattern).start
  }

}