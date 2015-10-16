package asterix.processing

import scala.concurrent.duration._

import play.api.libs.json._
import org.rogach.scallop._

import akka.actor.ActorSystem

import com.typesafe.config.{ConfigFactory, Config}

import commons.microservice.{Component, Microservice}

case class CatalogueItemProcessorComponent(args: Conf, settings: Config) extends Component {
  val name = "catalogue-item-processor"
  val runOnRole = "asterix-catalogue"
  def start(system: ActorSystem) = {
    system.actorOf(CatalogueItemProcessor.props(args, settings), name)
  }
}

class Conf(args: Seq[String]) extends ScallopConf(args) {
  val input   = opt[String](required = true)
  val storeId = opt[Long](required = true)
  val brandId = opt[Long](required = true)
  val brand   = opt[String]()
  val gender  = opt[String]()
}

object CatalogueItemProcessing {

  def main(args: Array[String]) {
    // command line argument config
    val argsConf  = new Conf(args)
    val inputFile = argsConf.input()

    // application config file
    val config = ConfigFactory.load("processing")
    val settings = config.getConfig("processing")

    // actor system
    val system = ActorSystem(settings.getString("actorSystem"), config)
    // Microservice(system).start(IndexedSeq(CatalogueItemProcessorComponent(argsConf, settings)))

    system.actorOf(CatalogueItemProcessor.props(argsConf, settings), "catalogue-item-processor")
  }

}
