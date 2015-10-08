package asterix
package crawler
package parsers
package pages

import scala.reflect.ClassTag
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.generic.CanBuildFrom

import play.api.libs.json._

import org.jsoup._, nodes.{Element => JSElement, Document => JSDocument, _}, select.{Elements => JSElements}

import scalaz.{Success => _, Failure => _, _}, Scalaz._

import elements._


object WebPageTypes {

  type Parser[+A] = ParseState => Result[A]

  trait Results[Result[+_]] { self =>

    implicit def operators[A](r: Result[A]) = ResultOps[A](r)

    def succeed[A](a: A): Result[A]
    def flatMap[A, B](r: Result[A])(f: A => Result[B]): Result[B]

    def or[A](ra: Result[A], rb: => Result[A]): Result[A] =
      ra match {
        case s: Success[_] => ra
        case f: Failure    => rb
      }

    def map[A, B](a: Result[A])(f: A => B): Result[B] =
      flatMap(a)(f andThen succeed)

    def sequence[A, M[X] <: TraversableOnce[X]](in: M[Result[A]])(implicit cbf: CanBuildFrom[M[Result[A]], A, M[A]]): Result[M[A]] =
      in.foldLeft(succeed(cbf(in))) { (rb, ra) =>
        (for(r <- rb; a <- ra) yield (r += a)) or rb
      } map (_.result())

    def optionT[A](in: Option[Result[A]]): Result[Option[A]] =
      in.map(_.map(_.some)) getOrElse succeed(none[A])

    case class ResultOps[A](r: Result[A]) {
      def flatMap[B](f: A => Result[B]) = self.flatMap(r)(f)
      def map[B](f: A => B) = self.map(r)(f)
      def or(r2: Result[A]) = self.or(r, r2)
    }

  }

  sealed trait Result[+A] {
    def extract: Either[ParseError, A] = this match {
      case Failure(e) => Left(e)
      case Success(a) => Right(a)
    }
  }

  case class Success[+A](get: A) extends Result[A]
  case class Failure(get: ParseError) extends Result[Nothing]

  object Results extends Results[Result] {
    def succeed[A](a: A): Result[A] = Success(a)
    def flatMap[A, B](r: Result[A])(f: A => Result[B]): Result[B] = r match {
      case Success(a) => f(a.asInstanceOf[A])
      case f: Failure => f
    }
  }

  case class ParseState(loc: DocLocation)

  trait DocLocation extends Location { self =>
    def toError(msg: String) = ParseError(List(self -> msg))
    def select(selector: String): JSElements
    def attr(attributeKey: String): String
    def html: String
    def id: String
    def ownText: String
    def text: String
    def value: String
    def classNames: Set[String]
    def parent: JSElement
    def parents: JSElements

    final protected def customSelect(e: JSElement, selector: String) = selector match {
      case "." => new JSElements(e)
      case _   => e.select(selector)
    }
  }

  case class Doc(doc: JSDocument) extends DocLocation {
    def select(selector: String)   = customSelect(doc, selector)
    def attr(attributeKey: String) = doc.attr(attributeKey)
    lazy val html                  = doc.html
    lazy val id                    = doc.id
    lazy val ownText               = doc.ownText
    lazy val text                  = doc.text()
    lazy val value                 = doc.`val`()
    lazy val classNames            = doc.classNames.asScala.toSet
    lazy val parent                = doc.parent
    lazy val parents               = doc.parents
  }

  case class Element(doc: JSDocument, element: JSElement) extends DocLocation {
    def select(selector: String)   = customSelect(element, selector)
    def attr(attributeKey: String) = element.attr(attributeKey)
    lazy val html                  = element.html
    lazy val id                    = element.id
    lazy val ownText               = element.ownText
    lazy val text                  = element.text()
    lazy val value                 = element.`val`()
    lazy val classNames            = element.classNames.asScala.toSet
    lazy val parent                = element.parent
    lazy val parents               = element.parents
  }

}

import WebPageTypes._

object WebPage extends Parsers[Parser] { self =>

  import Results.operators

  implicit def operators2[A](p: Parser[A]) = WebPageParserOps[A](p)
  implicit def operators3[A](a: A) = WebPageParserOps(succeed(a))

  // run the parse on a document
  def run[A](p: Parser[A])(doc: JSDocument): Either[ParseError, A] =
    p(ParseState(Doc(doc))).extract

  def succeed[A](a: A): Parser[A] = s => Success(a)

  def or[A](p1: Parser[A], p2: => Parser[A]) =
    s => p1(s) match {
      case f: Failure => p2(s)
      case r => r
    }

  override def map[A, B](p: Parser[A])(f: A => B) =
    s => p(s) match {
      case Success(a) => Success(f(a))
      case f: Failure => f
    }

  def flatMap[A, B](p: Parser[A])(f: A => Parser[B]) =
    s => p(s) match {
      case Success(a) => f(a)(s)
      case f: Failure => f
    }

  def as[A, B: ClassTag](p: Parser[A]): Parser[B] =
    s => p(s) match {
      case Success(a) =>
        if(implicitly[ClassTag[B]].runtimeClass.isInstance(a)) Success(a.asInstanceOf[B])
        else Failure(s.loc.toError("cannot cast"))
      case f: Failure => f
    }

  // apply the parser to an element identified by the selector
  def select[A](selector: String)(p: Parser[A]): Parser[List[A]] =
    s => Results.sequence(
      s.loc match {
        case d: Doc => d.select(selector).toList.map(ele => p(ParseState(Element(d.doc, ele))))
        case e: Element => e.select(selector).toList.map(ele => p(ParseState(Element(e.doc, ele))))
      }
    )

  def first[A](selector: String)(p: Parser[A]): Parser[Option[A]] =
    s => Results.optionT(
      s.loc match {
        case d: Doc => Option(d.select(selector).first).map(e => p(ParseState(Element(d.doc, e))))
        case e: Element => Option(e.select(selector).first).map(ele => p(ParseState(Element(e.doc, ele))))
      }
    )

  def pipe[A, B](p: Parser[A])(p2: => Parser[B])(implicit tps: ToParseState[A]): Parser[B] =
    s => p(s) match {
      case Success(a) => p2(tps.parseState(a.asInstanceOf[A]))
      case f: Failure => f
    }

  // DOM Extractors

  def attr(attributeKey: String): Parser[String] = s => Success(s.loc.attr(attributeKey))
  def html: Parser[String]                       = s => Success(s.loc.html)
  def id: Parser[String]                         = s => Success(s.loc.id)
  def ownText: Parser[String]                    = s => Success(s.loc.ownText)
  def text: Parser[String]                       = s => Success(s.loc.text)
  def value: Parser[String]                      = s => Success(s.loc.value)
  def classNames: Parser[Set[String]]            = s => Success(s.loc.classNames)
  def parent: Parser[JSElement]                  = s => Success(s.loc.parent)
  def parents: Parser[JSElements]                = s => Success(s.loc.parents)

  trait ToParseState[A] {
    def parseState(a: A): ParseState
  }

  class ElementToParseState[A <: JSElement] extends ToParseState[A] {
    def parseState(a: A) = a match {
      case doc: JSDocument => ParseState(Doc(doc))
      case ele: JSElement  => ParseState(Element(ele.ownerDocument, ele))
    }
  }

  implicit val elementToPS = new ElementToParseState[JSElement]
  implicit val docToPS = new ElementToParseState[JSDocument]

  case class WebPageParserOps[A](p: Parser[A]) {
    def +>[B](p2: => Parser[B])(implicit tps: ToParseState[A]) = self.pipe(p)(p2)
  }

}