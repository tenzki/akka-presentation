package com.tenzki.akkapresentation.clustering

import java.util.UUID

import akka.actor.{ReceiveTimeout, ActorSystem, Props}
import akka.cluster.Cluster
import akka.cluster.sharding.ShardRegion.Passivate
import akka.cluster.sharding.{ShardRegion, ClusterShardingSettings, ClusterSharding}
import akka.pattern.ask
import akka.persistence.PersistentActor
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration._

// commands
trait UserMsg {val id: UUID}
case class SetName(id: UUID, name: String) extends UserMsg
case class GetName(id: UUID) extends UserMsg

// events
case class NameSet(name: String)

case object Stop

class User extends PersistentActor {

  context.setReceiveTimeout(120.seconds)

  var name: String = null

  def receiveRecover: Receive = {
    case NameSet(changeName: String) => name = changeName
  }

  def receiveCommand: Receive = {
    case GetName(_) => sender() ! name
    case SetName(_, newName: String) => persist(NameSet(newName))(e => name = e.name)
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = Stop)
    case Stop => context.stop(self)
  }

  def persistenceId: String = s"user:persistence:${self.path.name}"
}

object User {
  val NAME = "user"

  val numberOfShards = 100

  val extractShardId: ShardRegion.ExtractShardId = {
    case command: UserMsg  => (command.id.toString.hashCode % numberOfShards).toString
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case command: UserMsg => (command.id.toString, command)
  }

}

object Clustering extends App {

  implicit val timeout = Timeout(5.seconds)
  val conf =
    """
      |akka {
      |  actor.provider = "akka.cluster.ClusterActorRefProvider"
      |  remote {
      |    log-remote-lifecycle-events = off
      |    netty.tcp {
      |      hostname = "127.0.0.1"
      |      port = 2551
      |    }
      |    watch-failure-detector.threshold = 20
      |  }
      |  cluster {
      |    seed-nodes = ["akka.tcp://clustering@127.0.0.1:2551"]
      |  }
      |  persistence.journal.plugin = "inmemory-journal"
      |}
    """.stripMargin

  val config = ConfigFactory.parseString(conf)

  implicit val system = ActorSystem("clustering", config)
  implicit val materializer = ActorMaterializer()

  val cluster = Cluster(system)
  val sharding = ClusterSharding(system)
  val shardingSettings = ClusterShardingSettings(system)

  val userShard = ClusterSharding(system).start(User.NAME, Props[User],
    ClusterShardingSettings(system), User.extractEntityId, User.extractShardId)

  val id = UUID.randomUUID()

  userShard ! SetName(id, "Marko")
  val getOriginalName = userShard ? GetName(id)
  val originalName = Await.result(getOriginalName, timeout.duration).asInstanceOf[String]
  println(originalName)

  userShard ! SetName(id, "Petar")
  val getNewName = userShard ? GetName(id)
  val newName = Await.result(getNewName, timeout.duration).asInstanceOf[String]
  println(newName)
}