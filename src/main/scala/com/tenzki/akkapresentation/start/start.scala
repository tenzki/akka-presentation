package com.tenzki.akkapresentation.start

import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration._

// messages
case object GetName
case class SetName(name: String)

// actor
class User(var name: String) extends Actor {

  override def receive: Receive = {
    case GetName => sender() ! name
    case SetName(newName: String) => name = newName
  }

}

object Start extends App {

  implicit val timeout = Timeout(5.seconds)
  val system = ActorSystem()
  val user = system.actorOf(Props(classOf[User], "Marko"))

  val getOriginalName = user ? GetName
  val originalName = Await.result(getOriginalName, timeout.duration).asInstanceOf[String]
  println(originalName)

  user ! SetName("Petar")

  val getNewName = user ? GetName
  val newName = Await.result(getNewName, timeout.duration).asInstanceOf[String]
  println(newName)

}