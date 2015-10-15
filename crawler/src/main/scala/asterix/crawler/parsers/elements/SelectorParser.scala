package asterix
package crawler
package parsers
package elements

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import scalaz._, Scalaz._

import play.api.libs.json._

import pages._, WebPageTypes._


private[elements] object SelectorParser {
  import WebPage._

  def unapply(config: java.util.Map[String, Any]): Option[Parser[JsValue]] =
    if(config.contains("selector")) {
      if(!config.contains("atKey")) createParser(config)
      else
        jsonMaker(config.get("atKey").asInstanceOf[String].split('.').toList) match {
          case None => none[Parser[JsObject]]
          case Some(mkJson) => createParser(config) map (_.map(mkJson(_)))
        }
    } else none[Parser[JsObject]]

  object Combiner {
    private val combineObject = (a: List[JsValue]) => {
      a.reduceOption { (a: JsValue, b: JsValue) =>
        (a, b) match {
          case (a: JsObject, b: JsObject) => a deepMerge b
          case (a: JsObject, _) => a
          case (_, b: JsObject) => b
          case _ => a
        }
      } getOrElse Json.obj()
    }

    private val combineArray =
      (a: List[JsValue]) =>
        a.foldLeft(Json.arr()) { (arr: JsArray, value: JsValue) =>
          arr :+ value
        }


    def unapply(c: String) = c match {
      case "combineAs(object)" => (combineObject).some
      case "combineAs(array)"  => (combineArray).some
      case _ => none[List[JsValue] => JsValue]
    }
  }

  private def jsonMaker(keys: List[String]) = keys.reverse match {
    case key :: Nil => ((value: JsValue) => Json.obj(key -> value)) some
    case head :: tail =>
      ((value: JsValue) =>
        tail.foldLeft(Json.obj(head -> value)) { (obj, key) =>
          Json.obj(key -> obj)
        }) some
    case Nil => None
  }

  private def createParser(config: java.util.Map[String, Any]) = {
    val parserO =
      config.get("parsers").asInstanceOf[java.util.ArrayList[Any]].asScala.toList match {
        case Nil => none[Parser[JsValue]]
        case head :: tail =>
          val parserO =
            head match {
              case ElementParser(parser) => parser.some
              case _ => none[Parser[JsValue]]
            }
          tail.foldLeft(parserO) { (parserO, tr) =>
            parserO.flatMap { pr =>
              tr match {
                case ParserTransformer(f) => pr.map(f).some
                case _ => none[Parser[JsValue]]
              }
            }
          }
      }

    val selector = config.get("selector").asInstanceOf[String]
    parserO flatMap { parser =>
      if(!config.contains("multiple")) first(selector)(parser).map(_ getOrElse Json.obj()).some
      else {
        config.get("multiple") match {
          case Combiner(c) =>
            select(selector)(parser).map(c(_)).some
          case _ => none[Parser[JsValue]]
        }
      }
    }
  }
}
