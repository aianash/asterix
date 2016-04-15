package asterix
package crawler


import scala.collection.mutable.{SortedSet => MSortedSet, ListBuffer}
import scala.concurrent.{Future, ExecutionContext}
import scala.sys.process._

import java.net.URL
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

import org.jsoup._, nodes.Document

import play.api.libs.json._

import com.typesafe.config.Config

import parsers._, pages.{WebPage, WebPageTypes}

//
trait Job {
  def id: Long
  def isRetry: Boolean
  def execute()(implicit ec: ExecutionContext): Future[List[CrawlerProtocol]]
  def attempt: Int
  def level: Int

  def json: String = "ghfghf"

  def get(urlstr: String)(implicit ec: ExecutionContext) = Future {
    Jsoup.connect(urlstr)
         .userAgent("Mozilla/5.0 (X11; U; Linux i686; pl-PL; rv:1.9.0.2) Gecko/20121223 Ubuntu/9.25 (jaunty) Firefox/3.8")
         .timeout(10000)
         .get()
  }
}

//
case class ExtractItemURLs(id: Long, url: URL, pattern: ItemListPageCrawlPattern, attempt: Int, isRetry: Boolean) extends Job {
  import WebPage._

  val level = JobQueue.LEVEL_3
  val batchSize = 10

  def execute()(implicit ec: ExecutionContext) =
    for {
      doc  <- get(url.toString)
      jobs <- execute(doc)
    } yield jobs

  //
  private def execute(doc: Document)(implicit ex: ExecutionContext) =
    run(pattern.parser)(doc) match {
      case Right((urls, inject)) =>
        val dtPgPattern = DetailPageCrawlPattern(pattern.host, pattern.config, inject)
        Future.sequence(
          urls.sliding(batchSize, batchSize) map { batch =>
            UUID.id("job") map { idO =>
              Push(DetailPage2Json(idO.get, batch, dtPgPattern, 1, isRetry))
            }
          } toList
        )
      case Left(pe) => Future.successful(List(Failed(this)))
    }

  def clone(id: Long, attempt: Int) = this.copy(id = id, attempt = attempt)

  override def toString = s"ExtractItemURLs[$id]"
}

//
case class DetailPage2Json(id: Long, batch: List[String], pattern: DetailPageCrawlPattern, attempt: Int, isRetry: Boolean) extends Job {
  import WebPage._
  import WebPageTypes.Parser

  val level = JobQueue.LEVEL_2

  private def parser(implicit ec: ExecutionContext): Future[Parser[List[JsObject]]] =
    Future.sequence(
      batch.map { url =>
        for {
          idO <- UUID.id("item")
          doc <- get(url)
        } yield (doc +> pattern.parser) map(_ ++ Json.obj("url" -> JsString(url), "id" -> idO.get)) // Future[Parser[JsObject]]
      }
    ) map(sequence(_))

  def execute()(implicit ec: ExecutionContext) =
    for {
      idO    <- UUID.id("job")
      jsonsP <- parser
    } yield jsonsP(null).extract match {
      case Right(jsons) => Push(FetchImages(idO.get, jsons, pattern.config, 1, isRetry)) :: List(Append(jsons))
      case _            => List(Failed(this))
    }

  def clone(id: Long, attempt: Int) = this.copy(id = id, attempt = attempt)

  override def toString = s"DetailPage2Json[$id]"
}

//
case class FetchImages(id: Long, batch: List[JsObject], config: Config, attempt: Int, isRetry: Boolean) extends Job {
  val outputdir = config.getString("image-output-dir")

  val level = JobQueue.LEVEL_1

  def execute()(implicit ex: ExecutionContext) = Future {
    batch.flatMap { json =>
      val id = (json \ "id").as[Long]
      (json \ "images").as[Seq[String]].zipWithIndex.map { x =>
        val imgType = getImgType(x._1)
        s"${id}_${x._2}.$imgType" -> x._1
      }
    } foreach {
      case (filename, url) =>
        println(s"\t Saving $url to $filename")
        new URL(url) #> new File(s"$outputdir/$filename") !
    }
    List.empty
  }

  def getImgType(url: String) = { "jpeg" }

  def clone(id: Long, attempt: Int) = this.copy(id = id, attempt = attempt)

  override def toString = s"FetchImages[$id]"
}

//
class JobQueue {
  import JobQueue.NUM_LEVELS

  val queues = Array.fill(NUM_LEVELS)({ new ConcurrentLinkedQueue[Job]() })
  val scheduledJobs = MSortedSet.empty(Ordering.by[Job, Long](_.id))

  def markScheduled(job: Job) { scheduledJobs += job }
  def markCompleted(job: Job) { scheduledJobs -= job }

  def isAnyScheduled = !scheduledJobs.isEmpty

  def add(job: Job) {
    val queue = queues(job.level)
    queue add job
  }

  def poll(num: Int) = {
    val buffer = ListBuffer.empty[Job]
    var left = num
    queues foreach { queue =>
      if(left > 0) {
        var job = queue.poll()
        var count = 1
        while(job != null && count < left) { buffer += job; job = queue.poll; count += 1 }
        left -= buffer.size
      }
    }
    buffer.result()
  }

  def numJobs = queues.foldLeft(0)(_ + _.size)
  def numScheduled = scheduledJobs.size
}

object JobQueue {
  val LEVEL_1 = 0
  val LEVEL_2 = 1
  val LEVEL_3 = 2

  val NUM_LEVELS = 3
}