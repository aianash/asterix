package asterix.processing

import scala.concurrent.duration._
import scala.collection.mutable.Map
import scala.io.Source
import scala.concurrent.{Future, Await}

import java.io._

import java.util.concurrent.TimeUnit

import play.api.libs.json._
import org.rogach.scallop._

import akka.actor.{Actor, ActorLogging, Props}
import akka.util.Timeout
import akka.pattern.pipe
import akka.routing.FromConfig
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._

import com.typesafe.config.Config

import hemingway.dictionary._

import commons.catalogue._, items._, attributes._
import commons.catalogue.collection._
import commons.owner.{StoreId, BrandId}

import cassie.core.protocols.catalogue._

import goshoplane.commons.core.services.{UUIDGenerator, NextId}
import goshoplane.commons.core.protocols.Implicits._


sealed trait CatalogueItemProcessorProtocols
case object StoreCatalogueItems extends CatalogueItemProcessorProtocols

class CatalogueItemProcessor(args: Conf, settings: Config) extends Actor with ActorLogging {

  import context.dispatcher

  val cluster = Cluster(context.system)
  cluster.subscribe(self, classOf[ClusterDomainEvent])

  val styleDict = InMemoryDictionary()
  val styles = ClothingStyle.styles map (_.name)
  styles foreach { x => styleDict += x }

  val itemTypeGroups = ItemTypeGroup.leaves
  val itemTypeDict = InMemoryDictionary()
  itemTypeGroups foreach { x => itemTypeDict += x.name }

  val sim = similarity.Jaccard(settings.getDouble("jaccard.alpha"))

  val UUID = context.actorOf(UUIDGenerator.props(settings.getInt("service.id"), settings.getInt("datacenter.id")))
  val catalogueService = context.actorOf(FromConfig.props(), name = "catalogue-service")

  val cij = new PrintWriter(new File("/tmp/catalogue-item-json"))

  def receive = {

    case MemberUp(actorRef) =>
      if(actorRef.address equals cluster.selfAddress) self ! StoreCatalogueItems

    case StoreCatalogueItems =>
      try {
        val inputFile = args.input()
        val iterator = Source.fromFile(inputFile).getLines.grouped(settings.getInt("batch-size"))
        iterator foreach { lines =>
          implicit val timeout = Timeout(2 seconds)
          val cisF = Future.sequence(lines map getCatalogueItem)
          val status = cisF flatMap { cis => catalogueService ?= InsertStoreCatalogueItem(CatalogueItems(cis)) }
          Await.result(status, 2 seconds)
        }
        cij.close()
      } catch {
        case ex: Exception => ex.printStackTrace
      }

  }

  private def getCatalogueItem(jsonStr: String): Future[CatalogueItem] = {
    implicit val timeout = Timeout(1 seconds)
    val catalogueItemF = (UUID ?= NextId("catalogue")) map { uuid =>
      val catalogueItem = processJson(jsonStr, uuid.get)
      cij.write(catalogueItem.json.toString + "\n")
      catalogueItem
    }
    catalogueItemF
  }

  private def processJson(jsonStr: String, uuid: Long) = {
    val json = Json.parse(jsonStr)

    // suggested values / ids
    val suggestedBrand  = args.brand.get
    val suggestedGender = args.gender.get
    val beandId         = args.brandId()
    val storeId         = StoreId(args.storeId())

    // ids
    val itemId    = CatalogueItemId(uuid)
    val variantId = VariantId(uuid)
    val brandId   = BrandId(beandId)

    // attributes that can be extracted directly
    val title       = (json \ "title").asOpt[String] map {x => ProductTitle(x) } getOrElse ProductTitle("")
    val price       = (json \ "price").asOpt[String] map {x => Price(x.replaceAll(",", "").toFloat)} getOrElse Price(0F)
    val description = (json \ "descr").asOpt[String] map { x => Description(x) } getOrElse Description("")
    val colors      = (json \ "colors").asOpt[Seq[String]] map { x => Colors(x.map(_.toUpperCase)) } getOrElse Colors(Seq.empty[String])
    val brand       = ((json \ "brand").asOpt[String] orElse suggestedBrand).map(Brand(_)).getOrElse(Brand(""))
    val gender      = ((json \ "gender").asOpt[String] orElse suggestedGender) map { x => Gender(x)} getOrElse Gender("")
    val itemUrl     = (json \ "url").asOpt[String] map { x => ItemUrl(x) } getOrElse ItemUrl("")
    val fit         = (json \ "detail" \ "Fit").asOpt[String] map { x => ApparelFit(x) } getOrElse ApparelFit("")
    val fabric      = (json \ "detail" \ "Material").asOpt[String] map { x => ApparelFabric(x) } getOrElse ApparelFabric("")

    // sizes
    val sizes =
      (json \ "sizes").asOpt[Seq[String]] map { x =>
        val s = x map extractSize
        ClothingSizes(s map {x => ClothingSize(x)})
      } getOrElse ClothingSizes(Seq.empty[Nothing])

    // item type group and item style
    val itemTypeGroupT     = ((json \ "itemTypes").asOpt[Seq[String]] map { getItemTypeGroup(gender.toString, _) }).get
    val itemTypeGroup      = itemTypeGroupT._2
    val itemTypesRemaining = (json \ "itemTypes").asOpt[Seq[String]] map { x => x.filter({ (y: String) => y != itemTypeGroupT._1 }) }
    val itemStyles         = ClothingStyles({itemTypesRemaining map getClothingStyle} get)
    val namedType          = NamedType(itemTypeGroup.name)

    // images and styling tips
    val images      = Images((json \ "primaryImage").asOpt[String].getOrElse(""), (json \ "images").asOpt[Seq[String]].getOrElse(Seq.empty[String]))
    val stylingTips = ((json \ "stylingTips").asOpt[String]).map(StylingTips(_)).getOrElse(StylingTips(""))

    buildCatalogueItem(storeId, itemTypeGroup, brandId, itemId, variantId, title, namedType, brand, price, sizes, colors, itemStyles, description, stylingTips, gender, images, itemUrl, fit, fabric)
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
    itemUrl       : ItemUrl,
    fit           : ApparelFit,
    fabric        : ApparelFabric
  ): CatalogueItem =
    itemTypeGroup match {
      case ItemTypeGroup.MensTShirt =>
        val branditem = MensTShirt.builder.forBrand
          .ids(brandId, itemId, variantId)
          .title(title)
          .namedType(namedType)
          .clothing(brand, price, sizes, colors, itemStyles, description, stylingTips, gender, images, itemUrl, fit, fabric)
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
          .clothing(brand, price, sizes, colors, itemStyles, description, stylingTips, gender, images, itemUrl, fit, fabric)
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
    itemGroup foreach { x =>
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

object CatalogueItemProcessor {
  def props(args: Conf, settings: Config) = Props(classOf[CatalogueItemProcessor], args, settings)
}
