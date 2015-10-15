package asterix.processing

import scala.io.Source
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.collection.mutable.Map

import play.api.libs.json._
import org.rogach.scallop._

import akka.actor.ActorSystem
import akka.util.Timeout
import akka.pattern.ask

import com.typesafe.config.ConfigFactory

import hemingway.dictionary._

import commons.catalogue._, items._, attributes._
import commons.owner.{StoreId, BrandId}

import cassie.catalogue.CatalogueService
import cassie.catalogue.protocols._

import goshoplane.commons.core.services.{UUIDGenerator, NextId}
import goshoplane.commons.core.protocols.Implicits._

class Conf(args: Seq[String]) extends ScallopConf(args) {
  val input   = opt[String](required = true)
  val storeId = opt[Long](required = true)
  val brandId = opt[Long](required = true)
  val brand = opt[String]()
  val gender = opt[String]()
}

object CatalogueItemProcessing {

  val styleDict = InMemoryDictionary()
  val styles = ClothingStyle.styles map (_.name)
  styles foreach { x => styleDict += x }

  val itemTypeGroups = ItemTypeGroup.leaves
  val itemTypeDict = InMemoryDictionary()
  itemTypeGroups foreach { x => itemTypeDict += x }

  val sim = similarity.Jaccard(0.2)

  def main(args: Array[String]) {
    // command line argument config
    val argsConf  = new Conf(args)
    val inputFile = argsConf.input()

    // application config file
    val config = ConfigFactory.load("processing")
    val settings = config.getConfig("processing")

    // actor system
    val system = ActorSystem(settings.getString("actorSystem"))
    // uuid generator
    val UUID = system.actorOf(UUIDGenerator.props(settings.getInt("service.id"), settings.getInt("datacenter.id")))
    // catalogue service actor
    val catalogueService = system.actorOf(CatalogueService.props)
    implicit val timeout = Timeout(2 seconds)

    val lines = Source.fromFile(inputFile).getLines
    lines foreach { line =>
      val uuidF = UUID ?= NextId("catalogue")
      uuidF foreach { uuid =>
        val storeitem = processJson(line, argsConf, uuid.get)
        val status = catalogueService ?= InsertStoreCatalogueItem(Seq(storeitem))
      }
    }
  }

  private def processJson(jsonStr: String, conf: Conf, uuid: Long) = {
    val json = Json.parse(jsonStr)

    // suggested values / ids
    val suggestedBrand = conf.brand.get
    val beandId        = conf.brandId()
    val storeId        = StoreId(conf.storeId())

    // ids
    val itemId    = CatalogueItemId(uuid)
    val variantId = VariantId(uuid)
    val brandId   = BrandId(beandId)

    // attributes that can be extracted directly
    val title       = (json \ "title").asOpt[String] map {x => ProductTitle(x) } getOrElse ProductTitle("")
    val price       = (json \ "price").asOpt[String] map {x => Price(x.replaceAll(",", "").toFloat)} getOrElse Price(0F)
    val description = (json \ "detail").asOpt[String] map { x => Description(x) } getOrElse Description("")
    val colors      = (json \ "colors").asOpt[Seq[String]] map { x => Colors(x.map(_.toUpperCase)) } getOrElse Colors(Seq.empty[String])
    val brand       = ((json \ "brand").asOpt[String] orElse(suggestedBrand)).map(Brand(_)).getOrElse(Brand(""))
    val namedType   = (json \ "itemType").asOpt[String] map { x => NamedType(x) } getOrElse NamedType("")
    val gender      = ((json \ "gender").asOpt[String] orElse conf.gender.get) map { x => Gender(x)} getOrElse Gender("")
    val itemUrl     = (json \ "url").asOpt[String] map { x => ItemUrl(x) } getOrElse ItemUrl("")

    // sizes
    val sizes =
      (json \ "sizes").asOpt[Seq[String]] map { x =>
        val s = x map extractSize
        ClothingSizes(s map {x => ClothingSize(x)})
      } getOrElse ClothingSizes(Seq.empty[Nothing])

    // item type group and item style
    val itemTypeGroupT = ((json \ "itemTypes").asOpt[Seq[String]] map { getItemTypeGroup(gender.toString, _) }).get
    val itemTypeGroup = itemTypeGroupT._2
    val itemTypesRemaining = (json \ "itemTypes").asOpt[Seq[String]] map { x => x.filter({ (y: String) => y != itemTypeGroupT._1 }) }
    val itemStyles = ClothingStyles({itemTypesRemaining map getClothingStyle} get)

    // images and styling tips
    val images = Images((json \ "image").asOpt[String].getOrElse(""), (json \ "images").asOpt[Seq[String]].getOrElse(Seq.empty[String]))
    val stylingTips = (json \ "stylingTips").asOpt[String].map(StylingTips(_)).getOrElse(StylingTips(""))

    buildCatalogueItem(storeId, itemTypeGroup, brandId, itemId, variantId, title, namedType, brand, price, sizes, colors, itemStyles, description, stylingTips, gender, images, itemUrl)
  }

  /**
   * Function to build catalogue item given all the attributes.
   * It matches for a particular item type
   */
  private def buildCatalogueItem(
    storeId       : StoreId,
    itemTypeGroup : ItemTypeGroup,
    brandId       : BrandId,
    itemId        : CatalogueItemId,
    variantId     : VariantId,
    title         : ProductTitle,
    namedType     : NamedType,
    brand         : Brand,
    price         : Price,
    sizes         : ClothingSizes,
    colors        : Colors,
    itemStyles    : ClothingStyles,
    description   : Description,
    stylingTips   : StylingTips,
    gender        : Gender,
    images        : Images,
    itemUrl       : ItemUrl
  ) =
    itemTypeGroup match {
      case ItemTypeGroup.MensTShirt =>
        val branditem = MensTShirt.builder.forBrand
          .ids(brandId, itemId, variantId)
          .title(title)
          .namedType(namedType)
          .clothing(brand, price, sizes, colors, itemStyles, description, stylingTips, gender, images, itemUrl)
          .build

        val storeitem = MensTShirt.builder.forStore.using(branditem)
          .ids(storeId, itemId, variantId)
          .build
        storeitem

      case ItemTypeGroup.WomensTops =>
        val branditem = WomensTops.builder.forBrand
          .ids(brandId, itemId, variantId)
          .title(title)
          .namedType(namedType)
          .clothing(brand, price, sizes, colors, itemStyles, description, stylingTips, gender, images, itemUrl)
          .build

        val storeitem = WomensTops.builder.forStore.using(branditem)
          .ids(storeId, itemId, variantId)
          .build
        storeitem
    }

  /**
   * Function to get item type group
   * It uses fuzzy matching on item group to get the best match for item type group
   * @type {[type]}
   */
  private def getItemTypeGroup(gender: String, itemGroup: Seq[String]) = {
    val itg2score = Map.empty[(String, ItemTypeGroup), Double]
    itemGroup map { x =>
      itemTypeDict.findSimilar(gender + x, sim, 1) foreach { x1 => itg2score += ((x, ItemTypeGroup(x1.str.get)) -> x1.score) }
    }
    itg2score.maxBy(_._2)._1
  }

  /**
   * Function to get clothing style.
   * It uses fuzzy maching on clothingStyles to get the bese matching clothing style
   * @type {[type]}
   */
  private def getClothingStyle(clothingStyles: Seq[String]) = {
    val bestMatches =
      clothingStyles.foldLeft(Seq.empty[String])((seq, style) =>
        styleDict.findSimilar(style, sim, 1).foldLeft(seq)((sq, st) => sq ++ st.str)
      )
    bestMatches map { x => ClothingStyle(x)}
  }

  /**
   * Extract the valid sizes from input argument.
   */
  private def extractSize(size: String) = {
    val regex1 = """[2-5]?[X|x]*[S|L|M|s|l|m]""".r
    val regex2 = """[0-9]{2}""".r
    ((regex1 findFirstIn size) orElse (regex2 findFirstIn size)).getOrElse("").toUpperCase
  }

}
