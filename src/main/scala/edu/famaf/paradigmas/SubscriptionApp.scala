package edu.famaf.paradigmas

import akka.actor.typed.ActorSystem
import org.json4s.JsonDSL._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.slf4j.{Logger, LoggerFactory}

import scala.io._

object SubscriptionApp extends App {
  implicit val formats = DefaultFormats

  val logger: Logger = LoggerFactory.getLogger("edu.famaf.paradigmas.SubscriptionApp")
  val subscriptionsFilePath: String = "./subscriptions.json"

  case class Feed(id: String, name: String, url: String, format: String)

  private def readSubscriptions(filename: String): List[Feed] = {
    val jsonContent = Source.fromFile(filename)
    (parse(jsonContent.mkString)).extract[List[Feed]]
  }

  val system = ActorSystem[Supervisor.SupervisorCommand](Supervisor(), "subscription-app")
  system ! Supervisor.LoadSubscriptions(readSubscriptions(subscriptionsFilePath))
}
