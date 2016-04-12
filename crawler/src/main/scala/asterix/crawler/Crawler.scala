package asterix
package crawler

import scala.concurrent.duration._
import scala.collection.mutable.{SortedSet => MSortedSet}
import scala.math.Ordering
import scala.util.{Success, Failure}

import java.io.{BufferedWriter, FileWriter, PrintWriter, StringReader}

import goshoplane.commons.core.services.{UUIDGenerator, NextId}
import goshoplane.commons.core.protocols.Implicits._

import play.api.libs.json._

import org.jsoup._

import akka.actor.{ActorLogging, Actor, Props}
import akka.routing.FromConfig
import akka.util.Timeout

import parsers.pages.WebPage


//
class Crawler extends Actor with ActorLogging {

  import context.dispatcher

  val MAX_JOB_ATTEMPT = 10

  val settings = CrawlerSettings(context.system)

  val scheduledJobs = MSortedSet.empty(Ordering.by[Job, Long](_.id))
  var jobs = MSortedSet.empty(Ordering.by[Job, Long](_.id))

  val retryLaterLog = new PrintWriter(new BufferedWriter(new FileWriter(settings.RETRY_LATER_LOG_FILE, true)))
  val failedLog = new PrintWriter(new BufferedWriter(new FileWriter(settings.FAILED_LOG_FILE, true)))

  val workers = context.actorOf(FromConfig.props(Props[Worker]), "workers")
  val uuid = context.actorOf(UUIDGenerator.props(1L, 1L), "uuid")

  val writer = new PrintWriter(new BufferedWriter(new FileWriter(settings.OUTPUT_FILE_PATH, true)))

  //
  def receive = {

    //
    case Push(job) =>
      implicit val timeout = Timeout(1 seconds)
      (uuid ?= NextId("job")) map(_.get) onComplete {
        case Success(id) => jobs += job.copy(id = id, attempt = job.attempt + 1)
        case Failure(ex) => log.error(ex, "Failed to generate uuid for the job = {}", job)
      }

    //
    case Schedule => getNewJobs(10) foreach { job =>
      markScheduled(job)
      workers ! job
    }

    //
    case Completed(job) =>
      markCompleted(job)
      if(scheduledJobs.isEmpty) { self ! Schedule }

    //
    case Failed(job) =>
      if(job.attempt <= MAX_JOB_ATTEMPT) self ! Push(job)
      else if(job.isRetry) failedLog.println(job.json)
      else retryLaterLog.println(job.json)

    case Append(jsons) =>
      jsons.foreach { js => writer.println(Json.stringify(js)) }
      writer.flush()
  }

  //
  def getNewJobs(num: Int) = {
    val (newJobs, leftJobs) = jobs.splitAt(num)
    jobs = leftJobs
    newJobs
  }

  //
  def markScheduled(job: Job) { scheduledJobs += job }

  //
  def markCompleted(job: Job) { scheduledJobs -= job }
}



// class Crawler(outputPath: String, pattern: CrawlPattern) {
  // import WebPage._

  // val writer = new PrintWriter(new BufferedWriter(new FileWriter(outputPath, true)))
  // val parser = pattern.parser
  // val doc = Jsoup.connect(pattern.url.toString)
  //                .userAgent("Mozilla/5.0 (X11; U; Linux i686; pl-PL; rv:1.9.0.2) Gecko/20121223 Ubuntu/9.25 (jaunty) Firefox/3.8")
  //                .timeout(10000)
  //                .get()

  // new PrintWriter(java.util.UUID.randomUUID.toString){ write(doc.outerHtml()); close }

  // def start: Unit = {
  //   println("Starting " + pattern.url)
  //   run(parser)(doc) match {
  //     case Right(data) => data.foreach { js => writer.println(Json.stringify(js)); writer.flush() }
  //     case _ =>
  //   }
  //   scala.sys.ShutdownHookThread(writer.close)
  // }

// }

//
object Crawler {
  def props() = Props[Crawler]
}