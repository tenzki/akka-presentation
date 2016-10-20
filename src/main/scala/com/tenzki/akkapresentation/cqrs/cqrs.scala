package com.tenzki.akkapresentation.cqrs

import java.util.UUID

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.pattern.ask
import akka.persistence.PersistentActor
import akka.persistence.inmemory.query.journal.scaladsl.InMemoryReadJournal
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration._

trait UserMsg {val id: UUID}
// commands
case class SetName(id: UUID, name: String) extends UserMsg

// events
case class NameSet(name: String)

case object Done

class UserProcessor extends PersistentActor {

  var name: String = null

  override def receiveRecover: Receive = {
    case NameSet(changeName: String) => name = changeName
  }

  def receiveCommand: Receive = {
    case SetName(_, newName: String) => persist(NameSet(newName))(e => name = e.name)
  }

  def persistenceId: String = s"user:cqrs:${self.path.name}"

}

// query
case class GetName(id: UUID) extends UserMsg

class UserView extends Actor with Stash {

  var name: String = null

  def receive: Receive = {
    case GetName(id: UUID) => stash()
    case EventEnvelope(_, _, _, NameSet(newName: String)) =>
      name = newName
      unstashAll()
      context.become(active)
      sender() ! Done
  }

  def active: Receive = {
    case GetName(_) => sender() ! name
    case EventEnvelope(_, _, _, NameSet(newName: String)) =>
      name = newName
      sender() ! Done
  }

}

object User {
  val PROCESSOR_NAME = "userProcessor"
  val VIEW_NAME = "userView"

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case command: UserMsg => (command.id.toString, command)
    case envelope: EventEnvelope => (envelope.persistenceId.substring(10), envelope)
  }

  val numberOfShards = 100

  val extractShardId: ShardRegion.ExtractShardId = {
    case command: UserMsg  => (command.id.toString.hashCode % numberOfShards).toString
    case EventEnvelope(_, persistenceId, _, _) => (persistenceId.substring(10).hashCode % numberOfShards).toString
  }
}

object CQRS extends App {

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
      |    seed-nodes = ["akka.tcp://cqrs@127.0.0.1:2551"]
      |  }
      |  persistence.journal.plugin = "inmemory-journal"
      |}
    """.stripMargin

  val config = ConfigFactory.parseString(conf)

  implicit val system = ActorSystem("cqrs", config)
  implicit val materializer = ActorMaterializer()

  val cluster = Cluster(system)
  val sharding = ClusterSharding(system)
  val shardingSettings = ClusterShardingSettings(system)

  val userProcessorShard = ClusterSharding(system).start(User.PROCESSOR_NAME, Props[UserProcessor],
    ClusterShardingSettings(system), User.extractEntityId, User.extractShardId)

  val userViewShard = ClusterSharding(system).start(User.VIEW_NAME, Props[UserView],
    ClusterShardingSettings(system), User.extractEntityId, User.extractShardId)

  val readJournal = PersistenceQuery(system)
    .readJournalFor[InMemoryReadJournal](InMemoryReadJournal.Identifier)

  readJournal.allPersistenceIds().filter(_.startsWith("user:cqrs:"))
    .runForeach { persistenceId =>
      readJournal.eventsByPersistenceId(persistenceId, 0, Long.MaxValue).mapAsync(1)(event => {
        userViewShard ? event
      }).runWith(Sink.ignore)
    }

  // test
  val id = UUID.randomUUID()

  userProcessorShard ! SetName(id, "Marko")
  val getOriginalName = userViewShard ? GetName(id)
  val originalName = Await.result(getOriginalName, timeout.duration).asInstanceOf[String]
  println(originalName)

  userProcessorShard ! SetName(id, "Petar")

  var viewSynced = false
  while (!viewSynced) {
    val getNewName = userViewShard ? GetName(id)
    val newName = Await.result(getNewName, timeout.duration).asInstanceOf[String]
    println(newName)
    viewSynced = "Petar".equals(newName)
  }

}