package com.tenzki.akkapresentation.persistence

import java.util.UUID

import akka.actor.{PoisonPill, Props, ActorSystem}
import akka.persistence.PersistentActor
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration._

// commands
case object GetName
case class SetName(name: String)

// events
case class NameSet(name: String)

// actor
class User(id: UUID) extends PersistentActor {

  var name: String = null

  override def receiveRecover: Receive = {
    case NameSet(changeName: String) => name = changeName
  }

  override def receiveCommand: Receive = {
    case GetName => sender() ! name
    case SetName(newName: String) => persist(NameSet(newName))(e => name = e.name)
  }

  override def persistenceId: String = s"user:${id.toString}"
}

object Persistence extends App {

  implicit val timeout = Timeout(5.seconds)

  val conf =
    """
      |akka {
      |  persistence.journal.plugin = "inmemory-journal"
      |}
    """.stripMargin

  val config = ConfigFactory.parseString(conf)

  val system = ActorSystem("persistence", config)
  val id = UUID.randomUUID()
  val user = system.actorOf(Props(classOf[User], id))

  user ! SetName("Marko")
  val getOriginalName = user ? GetName
  val originalName = Await.result(getOriginalName, timeout.duration).asInstanceOf[String]
  println(originalName)


  user ! SetName("Petar")
  val getNewName = user ? GetName
  val newName = Await.result(getNewName, timeout.duration).asInstanceOf[String]
  println(newName)

  user ! PoisonPill

  val user2 = system.actorOf(Props(classOf[User], id))

  val getCurrentName = user2 ? GetName
  val currentName = Await.result(getCurrentName, timeout.duration).asInstanceOf[String]
  println(currentName)
}