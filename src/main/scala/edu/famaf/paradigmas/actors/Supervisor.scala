package edu.famaf.paradigmas
 
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.util.Timeout
import java.io.{File, PrintWriter}
 
import scala.collection.mutable.{Map=>Dict}
import scala.concurrent.duration._
import scala.util.{Success,Failure}

import dispatch.{Http}
 
object Supervisor {
  def apply(): Behavior[SupervisorCommand] = Behaviors.setup(context => new Supervisor(context))
 
  sealed trait SupervisorCommand
  final case class LoadSubscriptions(subscriptions: List[SubscriptionApp.Feed])
    extends SupervisorCommand
  final case class GetAllFeedsContent()
    extends SupervisorCommand
  final case class SuccessFeedContent(feedName: String, feedContent: Seq[String])
    extends SupervisorCommand
  final case class FailureFeedContent(failedFeedId: String, failureMessage: String)
    extends SupervisorCommand

  final case class DumpSuscriptionResponse(status: String, content: String, fileName: String)
    extends SupervisorCommand

  final case class SuccessfullDump()
    extends SupervisorCommand

  final case class UnsuccessfullDump(failureMessage: String)
    extends SupervisorCommand
}

class Supervisor(context: ActorContext[Supervisor.SupervisorCommand])
      extends AbstractBehavior[Supervisor.SupervisorCommand](context) {

  context.log.info("Supervisor Started")

  import Supervisor._

  val feedsDict: Dict[String, ActorRef[Subscription.SubscriptionCommand]] = Dict()
  var dumpedFeedsNumber = 0

  implicit val timeout: Timeout = (3).seconds

  private def  cleanDumpId(str: String) = {
    val validChars = "abcdefghijklmnñopqrstuvwxyzABCDEFGHIJKLMNÑOPQRSTUVWXYZ".toCharArray()
    def toValid(c:Char): Char =
      if (validChars.contains(c))
        c
      else
        '_'
    str.map(toValid)
  }

  override def onMessage(msg: SupervisorCommand): Behavior[SupervisorCommand] = {
    msg match {
      case LoadSubscriptions(subscriptions) => {

        subscriptions.foreach { feed: SubscriptionApp.Feed =>
          val subscription: ActorRef[Subscription.SubscriptionCommand] =
            context.spawn(Subscription(feed.name, feed.url, feed.format), feed.id)
          feedsDict.put(feed.id, subscription)
        }

        context.self ! GetAllFeedsContent()

        Behaviors.same
      }

      case GetAllFeedsContent() => {
        // Get content from all the feedsDict
        feedsDict.keys.foreach {
          feedId: String => context.ask(feedsDict(feedId), Subscription.GetFeedContent) {
            case Success(Subscription.FeedContent(name, content)) =>
              SuccessFeedContent(name, content)
            case Failure(exception) =>
              FailureFeedContent(feedId, exception.getMessage)
          }
        }

        Behaviors.same
      }

      case SuccessFeedContent(feedName, feedContent) =>

        val status = "success"
        val content = feedContent.mkString("\n")

        context.self ! DumpSuscriptionResponse(status, content, cleanDumpId(feedName))
        Behaviors.same

      case FailureFeedContent(failedFeedId, errorMessage) =>

        val status = "failure"

        context.self ! DumpSuscriptionResponse(status, errorMessage, cleanDumpId(failedFeedId))
        Behaviors.same

      case DumpSuscriptionResponse(status, content, fileName) =>

        val dumpRef = context.spawn(Dumper(status, content, fileName), "Dumper" + fileName)
        context.ask(dumpRef, Dumper.Dump) {
          case Success(Dumper.FinishedDump()) =>
            SuccessfullDump()
          case Failure(exception) =>
            UnsuccessfullDump(exception.getMessage)
        }

        Behaviors.same

      case SuccessfullDump() =>

        context.log.info("dumped!")
        dumpedFeedsNumber += 1

        if(dumpedFeedsNumber == feedsDict.size)
          Behaviors.stopped
        else
          Behaviors.same

      case UnsuccessfullDump(errorMessage) =>

        context.log.error(s"dump failed: ${errorMessage}")
        dumpedFeedsNumber += 1

        if(dumpedFeedsNumber == feedsDict.size)
          Behaviors.stopped
        else
          Behaviors.same
    }
  }

  override def onSignal: PartialFunction[Signal, Behavior[SupervisorCommand]] = {
    case PostStop =>
      context.log.info("Supervisor Stopped")
      Http.default.shutdown()
      this
  }
}