package asterix
package crawler

import scala.concurrent.duration._
import scala.concurrent.{Future, ExecutionContext}
import scala.collection.mutable.{SortedSet => MSortedSet}
import scala.math.Ordering
import scala.util.{Success, Failure}

import java.io.{BufferedWriter, FileWriter, PrintWriter, StringReader}

import goshoplane.commons.core.services.NextId
import goshoplane.commons.core.protocols.Implicits._

import play.api.libs.json._

import org.jsoup._

import akka.actor.{ActorLogging, Actor, Props, ActorRef}
import akka.routing.FromConfig
import akka.util.Timeout

import parsers.pages.WebPage


//
class Crawler extends Actor with ActorLogging {

  import context.dispatcher

  val MAX_JOB_ATTEMPT = 10

  val settings = CrawlerSettings(context.system)

  var jobQueue = new JobQueue

  val retryLaterLog = new PrintWriter(new BufferedWriter(new FileWriter(settings.RETRY_LATER_LOG_FILE, true)))
  val failedLog = new PrintWriter(new BufferedWriter(new FileWriter(settings.FAILED_LOG_FILE, true)))

  val workers = context.actorOf(FromConfig.props(Props[Worker]), "workers")

  val writer = new PrintWriter(new BufferedWriter(new FileWriter(settings.OUTPUT_FILE_PATH, true)))

  context.system.scheduler.schedule(5 seconds, 1 seconds, self, ShowStats)

  //
  def receive = {

    //
    case Push(job) =>
      jobQueue add job
      log.info("Pushed a new job = {}", job)

    //
    case Schedule =>
      val jobs = jobQueue.poll(10)
      if(jobs.isEmpty) context.system.scheduler.scheduleOnce(1 seconds, self, Schedule)
      else {
        jobs.foreach { job =>
          log.info("Scheduling job = {}, to workers", job)
          jobQueue.markScheduled(job)
          workers ! job
        }
      }

    //
    case Completed(job) =>
      jobQueue.markCompleted(job)
      log.info("Completed job = {}", job)
      if(!jobQueue.isAnyScheduled) { self ! Schedule }

    //
    case Failed(job) =>
      log.info("Failed job = {}", job)
      jobQueue.markCompleted(job)
      if(job.attempt <= MAX_JOB_ATTEMPT) self ! Push(job)
      else if(job.isRetry) failedLog.println(job.json)
      else retryLaterLog.println(job.json)
      if(!jobQueue.isAnyScheduled) { self ! Schedule }

    //
    case Append(jsons) =>
      jsons.foreach { js => writer.println(Json.stringify(js)) }
      writer.flush()

    //
    case ShowStats =>
      log.info("JobQueue number of jobs = {}, num jobs scheduled = {}", jobQueue.numJobs, jobQueue.numScheduled)
  }

}

//
object Crawler {
  def props() = Props[Crawler]
}

object UUID {
  private var uuid: ActorRef = null
  def uuid(actor: ActorRef) { uuid = actor }
  implicit val timeout = Timeout(10 seconds)
  def id(idFor: String)(implicit ec: ExecutionContext) = (uuid ?= NextId(idFor))
}