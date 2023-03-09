package edu.famaf.paradigmas

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.Behaviors

import java.nio.channels.AsynchronousFileChannel
import java.nio.file.{Path, Paths, Files, FileAlreadyExistsException}
import java.nio.file.StandardOpenOption
import java.nio.ByteBuffer
import java.nio.charset.Charset

import scala.util._
import scala.concurrent._
import scala.concurrent.duration._ // no?
import ExecutionContext.Implicits.global // no?

object Dumper {
  def apply(status: String, text: String, fileName: String): Behavior[DumperCommand] =
      Behaviors.setup(context => new Dumper(context, status, text, fileName))

  sealed trait DumperCommand

  sealed trait DumperResponse
  final case class Dump(replyTo: ActorRef[DumperResponse])
    extends DumperCommand

  final case class DumpSuccess(replyTo: ActorRef[DumperResponse])
    extends DumperCommand

  final case class DumpFailure(exception: String)
    extends DumperCommand

  final case class FinishedDump()
    extends DumperResponse
}

class Dumper(context: ActorContext[Dumper.DumperCommand],
             val status: String, val text: String, val fileName: String)
                extends AbstractBehavior[Dumper.DumperCommand](context) {

  import Dumper._

  val dirPathStr = "Dumps"
  val outputPathStr = dirPathStr + s"/${fileName}.out"

  val header =
    status match {
      case "success" => s"Content from feed ${fileName}: "
      case "failure" => s"There was a problem getting the subscription content: "
    }

  private def str_to_bb(msg: String): ByteBuffer = {
    ByteBuffer.wrap(msg.getBytes(Charset.defaultCharset()))
  }

  private def writeOnDisc() = Future {

    val dirPath = Paths.get(dirPathStr)
    val outputPath: Path = Paths.get(outputPathStr)

    try {
      Files.createDirectory(dirPath)
    } catch {
      case e: FileAlreadyExistsException => ()
    }

    val asyncFile = AsynchronousFileChannel.open(
      outputPath,
      StandardOpenOption.WRITE,
      StandardOpenOption.CREATE,
    )

    val bytebuffer = str_to_bb(header + text)
    asyncFile.write(bytebuffer, 0)

    ()
  }

  override def onMessage(msg: DumperCommand): Behavior[DumperCommand] = {
    msg match {
      case Dump(replyTo) =>
        context.pipeToSelf(writeOnDisc) {
          case Success(_) => DumpSuccess(replyTo)
          case Failure(exception) => DumpFailure(exception.getMessage)
        }

        Behaviors.same

      case DumpSuccess(replyTo) =>
        replyTo ! FinishedDump()
        Behaviors.stopped

      case DumpFailure(errorMessage) =>
        context.log.error(s"There was a problem dumping to disc: ${errorMessage}")
        Behaviors.stopped
    }
  }

  override def onSignal: PartialFunction[Signal, Behavior[DumperCommand]] = {
    case PostStop =>
      context.log.info("dumper Stopped")
      this
  }
}
