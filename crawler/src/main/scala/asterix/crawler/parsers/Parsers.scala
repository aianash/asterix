package asterix
package crawler
package parsers

import scala.reflect.ClassTag
import scala.collection.generic.CanBuildFrom

import com.typesafe.config._

import org.jsoup._, nodes._, select._


trait Parsers[Parser[+_]] { self =>
  def run[A](p: Parser[A])(doc: Document): Either[ParseError, A]

  implicit def operators[A](p: Parser[A]) = ParserOps[A](p)

  def succeed[A](a: A): Parser[A]
  def or[A](p1: Parser[A], p2: => Parser[A]): Parser[A]
  def flatMap[A, B](p: Parser[A])(f: A => Parser[B]): Parser[B]
  def as[A, B: ClassTag](p: Parser[A]): Parser[B]

  def foreach[A](p: Parser[A])(f: A => Unit): Unit = map(p)(f)

  def sequence[A, M[X] <: TraversableOnce[X]](in: M[Parser[A]])(implicit cbf: CanBuildFrom[M[Parser[A]], A, M[A]]): Parser[M[A]] =
    in.foldLeft(succeed(cbf(in))) { (pr, pa) =>
      (for(r <- pr; a <- pa) yield (r += a)) or pr
    } map (_.result())

  def product[A, B](p: Parser[A], p2: => Parser[B]): Parser[(A, B)] =
    flatMap(p)(a => map(p2)(b => (a, b)))

  def map2[A, B, C](p: Parser[A], p2: => Parser[B])(f: (A, B) => C): Parser[C] =
    for { a <- p; b <- p2 } yield f(a, b)

  def map[A, B](a: Parser[A])(f: A => B): Parser[B] =
    flatMap(a)(f andThen succeed)


  case class ParserOps[A](p: Parser[A]) {

    def |[B >: A](p2: => Parser[B]): Parser[B] = self.or(p,p2)
    def or[B >: A](p2: => Parser[B]): Parser[B] = self.or(p,p2)

    def flatMap[B](f: A => Parser[B]): Parser[B] =
      self.flatMap(p)(f)

    def foreach(f: A => Unit): Unit = self.foreach(p)(f)

    def map[B](f: A => B): Parser[B] = self.map(p)(f)

    def **[B](p2: => Parser[B]): Parser[(A, B)] =
      self.product(p, p2)
    def product[B](p2: => Parser[B]): Parser[(A, B)] =
      self.product(p, p2)

    def as[B: ClassTag] = self.as[A, B](p)
  }

}

case class ParseError(stack: List[(Location, String)] = List())

trait Location {
  def toError(msg: String): ParseError
}