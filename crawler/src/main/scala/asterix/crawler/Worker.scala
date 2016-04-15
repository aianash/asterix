package asterix
package crawler

import scala.util.{Success, Failure}

import akka.actor.{Actor, ActorLogging}
import akka.pattern.pipe


//
class Worker extends Actor with ActorLogging {

  import context.dispatcher

  def receive = {
    case job: Job =>
      val replyTo = sender()
      log.info("Worker received job = {}", job)
      job.execute() onComplete {
        case Success(msgs) =>
          msgs foreach ( replyTo ! _)
          replyTo ! Completed(job)
        case Failure(ex) => replyTo ! Failed(job)
      }
  }
}