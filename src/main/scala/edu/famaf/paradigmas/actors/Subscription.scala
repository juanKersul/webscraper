package edu.famaf.paradigmas
 
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.Behaviors
 
import dispatch._, Defaults._
import scala.concurrent.Future
import scala.xml.XML
import scala.util.{Success,Failure}

import org.json4s.JsonDSL._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.DefaultFormats._

 
object Subscription {
  def apply(name: String, url: String, format: String): Behavior[SubscriptionCommand] =
    Behaviors.setup(context => new Subscription(context, name, url, format))
 
  sealed trait SubscriptionCommand
  final case class GetFeedContent(replyTo: ActorRef[SubscriptionResponse]) extends SubscriptionCommand
  final case class ContentSuccess(content: Seq[String], replyTo: ActorRef[SubscriptionResponse])
    extends SubscriptionCommand
  final case class ContentFailure(exception: String) extends SubscriptionCommand
 
  sealed trait SubscriptionResponse
  final case class FeedContent(name: String, content: Seq[String])
    extends SubscriptionResponse

  case class Content(selftext: String, title: String)
  def blockingGetContentJson(feedBody: String): Seq[String] = {

    implicit val formats = DefaultFormats

    def dictToStr(content: Content): String =
      s"title:\n\t${content.title}\nselftext:\n\t${content.selftext}"

    val data: List[Content] =
      (parse(feedBody) \ "data" \ "children" \ "data").extract[List[Content]]

    data.map(dictToStr).toSeq
  }
}

class Subscription(context: ActorContext[Subscription.SubscriptionCommand],
                   feedName: String, feedUrl: String, feedFormat: String)
      extends AbstractBehavior[Subscription.SubscriptionCommand](context) {

  import Subscription._

  private def blockingGetContent(feedBody: String): Seq[String] = {
    feedFormat match {
      case "xml" => blockingGetContentXml(feedBody)
      case "json" => blockingGetContentJson(feedBody)
    }
  }

  private def blockingGetContentXml(feedBody: String): Seq[String] = {

    val xml = XML.loadString(feedBody)

    (xml \\ "item") map {
      item =>
        (item \ "title").text + " " + (item \ "description").text
    }
  }

  private def queryURL(): Future[String] = Http.default(url(feedUrl) OK as.String)

  private def getContent(): Future[Seq[String]] = {
    queryURL.flatMap { feedBody: String => Future {
        blockingGetContent(feedBody)
      }
    }
  }

  override def onMessage(msg: SubscriptionCommand): Behavior[SubscriptionCommand] = {
    msg match {
      case GetFeedContent(replyTo) =>
        context.pipeToSelf(getContent) {
          case Success(content) => ContentSuccess(content, replyTo)
          case Failure(exception) => ContentFailure(exception.getMessage)
        }
        Behaviors.same
      case ContentSuccess(content, replyTo) =>
        replyTo ! FeedContent(feedName, content)
        Behaviors.stopped
      case ContentFailure(errorMessage) =>
        context.log.error(s"There was a problem getting the subscription content: ${errorMessage}")
        Behaviors.stopped
    }
  }

  override def onSignal: PartialFunction[Signal, Behavior[SubscriptionCommand]] = {
    case PostStop =>
      context.log.info("Subscription Stopped")
      this
  }
}