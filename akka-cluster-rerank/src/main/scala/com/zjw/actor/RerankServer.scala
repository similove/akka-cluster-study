package com.zjw.actor

import scala.concurrent.duration._

import akka.actor.{Actor, Props, RootActorPath}
import akka.cluster.{Cluster, Member, MemberStatus}
import akka.cluster.ClusterEvent._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import com.zjw.config.Config
import com.zjw.context.{AkkaContext, TestMsg}
import com.zjw.entity.{RerankRequest, ServerRegistration}
import com.zjw.handler.RerankHandlerV1
import com.zjw.rpc.akka.ZookeeperClusterSeed
import com.zjw.util.JacksonUtil
import org.apache.logging.log4j.scala.Logging

/**
 * @author zjw <cn.zjwblog@gmail.com> 2019/12/17 上午10:12
 *
 */
class RerankServer extends Actor with Logging {

  val cluster = Cluster(context.system)

  override def preStart(): Unit = {
    cluster.subscribe(
      self,
      classOf[MemberUp],
      classOf[UnreachableMember],
      classOf[MemberRemoved],
      classOf[MemberEvent]
    )
  }

  override def postStop(): Unit = cluster.unsubscribe(self)

  implicit val timeout: Timeout = Timeout(Config.commonTimeout.millisecond)

  override def receive: Receive = {
    case msg: TestMsg =>
      logger.info(s"receive $msg")

    case state: CurrentClusterState =>
      state.members.filter(_.status == MemberStatus.Up) foreach register

    case request: RerankRequest =>
      sender() ! RerankHandlerV1.distHandler(request)

    // 当Engine端启动, 通知到Rerank端,Engine上线, Rerank端可以前去注册
    case ServerRegistration("engine") =>
      logger.info(s"==== register ${ServerRegistration("engine")}")
      // 向Engine端注册
      sender() ! ServerRegistration("rerank")

    case MemberUp(member) =>
      logger.info(s"Member is Up: ${member.address}")
      //主动向Engine端注册
      register(member)

    case msg: Any =>
      logger.info(s"receive unknown actor message [ $msg ].")
  }

  def register(member: Member): Unit = {
    logger.info(s"send rerank register message to ${member.address} with role ${member.roles}.")
    if (member.hasRole("engine")) {
      context.actorSelection(RootActorPath(member.address) / "user" / "engine") !
        ServerRegistration("rerank")
    } else if (member.hasRole("recall")) {
      context.actorSelection(RootActorPath(member.address) / "user" / "recall") !
        ServerRegistration("rerank")
    } else if (member.hasRole("rerank")) {
      context.actorSelection(RootActorPath(member.address) / "user" / "rerank") !
        ServerRegistration("rerank")
    }
  }
}

object RerankServer extends Logging {
  def initAkkaCompone(): Unit = {
    val config = ConfigFactory
      .parseString("akka.cluster.roles = [rerank]")
      .withFallback(AkkaContext.conf)
    AkkaContext.initActorSystem(Option(config))

    val system = AkkaContext.getSystem

    val zkS = ZookeeperClusterSeed(system)

    zkS.join()

  }

  def main(args: Array[String]): Unit = {
    RerankServer.initAkkaCompone()
    AkkaContext.getRouter("rerank", classOf[RerankServer], "my-dispatcher")
    var cnt = 1
    while (true) {
      Thread.sleep(60000)
      val cluster = Cluster(AkkaContext.getSystem)
      cluster.state.members.map(JacksonUtil.prettyPrint).foreach(println)
      // println(JacksonUtil.prettyPrint(cluster.state.leader))
      cnt += 1
      if (cnt % 100 == 0) {
        ZookeeperClusterSeed.zookeeperClusterZeed.client.close()
      }
    }

  }
}
