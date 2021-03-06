package actors

import play.api._
import play.api.libs.iteratee._
import play.api.libs.concurrent._
import play.api.libs.iteratee.Concurrent._
import play.api.Play.current
import akka.actor._
import akka.actor.Actor._
import scalaz.OptionW
import scalaz.Scalaz._

import models._

class StoryActor extends Actor {

  import StoryActor._

  private var projects: Map[String, Set[Channel[Log]]] = Map.empty

  def receive = {

    case Listen(project: String) => {
      lazy val channel: Enumerator[Log] = Concurrent.unicast(
        channel => self ! Init(project, channel),
        onComplete = self ! Stop(project),
        onError = { case(error, _) =>
          Logger.error("[Actor] Error during stream log for %s : %s".format(project,error))
        }
      )
      sender ! channel
    }

    case Init(project, channel) => {
      Logger.info("[Actor] Stream project " + project)
      val channels: Set[Channel[Log]] = findChannels(project)
      projects += (project -> (channels + channel))
    }

    case IsAlive() => projects.foreach {
        case (project, channels) => {
          channels.foreach(channel => {
            //Logger.debug("[Actor] Project %s alive".format(project))
            channel.push(Input.Empty)
          })
        }
    }

    case Stop(project: String) => {
      Logger.warn("[Actor] Stream %s has been closed ...".format(project))
      projects = projects.filter(p => p._1 != project)
    }

    case NewLog(log: Log) => {
      Project.createIfNot(Project(log.project, log.project))
      pushToChannel(log.project, log)
    }
  }

  private def findChannels(projectName: String): Set[Channel[Log]] = {
    projects.find(_._1 == projectName).map(_._2).getOrElse(Set.empty)
  }

  private def pushToChannel(project: String, log: Log) = {
    findChannels(Project.ALL).foreach { channel =>
      channel.push(log)
    }

    if(project != Project.ALL) {
      findChannels(log.project).foreach { channel =>
        channel.push(log)
      }
    }
  }
}

object StoryActor {
  import play.api.Play.current
  import akka.util.duration._

  sealed trait Event
  case class Listen(project: String) extends Event
  case class Stop(project: String) extends Event
  case class NewLog(log: Log)
  case class Init(project: String, p: Channel[Log])
  case class IsAlive()

  lazy val system = ActorSystem("storyroom")
  lazy val ref = Akka.system.actorOf(Props[StoryActor])

  def start = Akka.system.scheduler.schedule(1 second, 1 second) {
      StoryActor.ref ! IsAlive()
  }
}
