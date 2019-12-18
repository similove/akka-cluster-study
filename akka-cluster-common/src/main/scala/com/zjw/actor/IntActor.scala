package com.zjw.actor

import scala.util.Random

import akka.actor.{Actor, ActorRef, Props, Terminated}
import akka.cluster.{Cluster, MemberStatus}
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}
import akka.event.Logging
import com.typesafe.config.ConfigFactory
import com.zjw.context.{AkkaContext, TestMsg}

/**
 * @author zjw <cn.zjwblog@gmail.com> 2019/12/16 下午8:09
 *
 */
class IntActor extends Actor {
  val logger = Logging(context.system, this)
  var recallInstances = IndexedSeq.empty[ActorRef]
  var rerankInstances = IndexedSeq.empty[ActorRef]

  val cluster = Cluster(context.system)

  val random = new Random()

  override def preStart(): Unit = cluster.subscribe(self, classOf[MemberUp])

  override def postStop(): Unit = cluster.unsubscribe(self)

  def receive: Receive = {
    case msg: TestMsg =>
      if (rerankInstances.isEmpty) {
        logger.info("no avaliable rerank node.")
      }
      rerankInstances foreach {
        case r => r forward msg
      }
//    case _: IntActorRequest if rerankInstances.isEmpty =>
//      throw new RuntimeException("Service unavailable, please try again later")
//    case req: IntActorRequest =>
//      val totalStep = rerankInstances.size
//      logger.info(s"rerank akka node size: $totalStep")
//      val result = random.nextInt(100)
//      sender() ! result
//
//    case ServerRegistration("rerank") if !rerankInstances.contains(sender()) =>
//      logger.info(s"register rerank node ${sender()}.")
//      context watch sender()
//      rerankInstances = rerankInstances :+ sender()
//
//    case Terminated(a) =>
//      logger.info(s"terminated node ${a}")
//      rerankInstances = rerankInstances.filterNot(_ == a)
//
//    case MemberUp(m) => notify(m)
//
//    case state: CurrentClusterState =>
//      state.members.filter(_.status == MemberStatus.Up) foreach notify

    case msg: Any =>
      logger.info(s"receive unknown actor message [ ${msg} ].")
  }
}


object IntActor {
  def initAkkaCompone(): Unit = {
    val config = ConfigFactory.parseString("akka.cluster.roles = [engine]")
      .withFallback(AkkaContext.conf)
    AkkaContext.initActorSystem(Option(config))
    val system1 = AkkaContext.getSystem
//    val zkS1 = ZookeeperClusterSeed(system1)
//    zkS1.join()
  }

  def main(args: Array[String]): Unit = {
    initAkkaCompone()
    val system = AkkaContext.getSystem
    val frontend = system.actorOf(Props[IntActor], name = "rs-actor-server")

    import scala.concurrent.duration._

    import system.dispatcher
    system.scheduler.schedule(2.seconds, 2.seconds) {
      frontend ! TestMsg("yyy")
    }
  }
}