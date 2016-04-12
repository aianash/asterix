package asterix
package crawler

import scala.util.{Success, Failure}

import akka.actor.{Actor, ActorLogging}
import akka.pattern.pipe

//d
class Worker extends Actor {

  import context.dispatcher

  def receive = {
    case job: Job =>
      job.execute() onComplete {
        case Success(msgs) =>
          msgs foreach ( context.parent ! _)
          context.parent ! Completed(job)
        case Failure(ex) =>
          context.parent ! Failed(job)
      }
  }
}