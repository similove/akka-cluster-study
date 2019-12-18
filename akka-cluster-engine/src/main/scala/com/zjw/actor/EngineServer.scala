package com.zjw.actor

import java.util.Random

import scala.concurrent.duration._

import akka.actor.{Actor, ActorRef, Props, RootActorPath, Terminated}
import akka.cluster.{Cluster, Member, MemberStatus}
import akka.cluster.ClusterEvent._
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import com.zjw.config.Config
import com.zjw.context.{AkkaContext, TestMsg}
import com.zjw.entity.{RecallRequest, RerankRequest, RerankResponse, ServerRegistration}
import com.zjw.rpc.akka.ZookeeperClusterSeed
import org.apache.logging.log4j.scala.Logging

/**
 * @author zjw <cn.zjwblog@gmail.com> 2019/12/16 下午8:34
 *
 */
class EngineServer extends Actor with Logging {
  private[this] val random = new Random()

  var recallInstances: IndexedSeq[ActorRef] = IndexedSeq.empty[ActorRef]
  var rerankInstances: IndexedSeq[ActorRef] = IndexedSeq.empty[ActorRef]

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
    // 测试消息，该消息会发送到所有RecallServer和RerankServer
    case msg: TestMsg =>
      // 检测所有recallServer
      if (recallInstances.isEmpty) {
        logger.warn("No avaliable recall node.")
      }
      recallInstances foreach { r => r forward msg }
      // 检测所有rerankServer
      if (rerankInstances.isEmpty) {
        logger.warn("No avaliable rerank node.")
      }
      rerankInstances foreach { r => r forward msg }

    // 当接收到RecallServer的请求时，如果没有注册的RecallServer服务，则抛出异常
    case _: RecallRequest if recallInstances.isEmpty =>
      throw new RuntimeException("Service unavailable, please try again later")

    // 当接收到RerankServer的请求时，如果没有注册的RerankServer服务，则抛出异常
    case _: RerankRequest if rerankInstances.isEmpty =>
      throw new RuntimeException("Service unavailable, please try again later")

    // 远程调用RecallServer
    case request: RecallRequest =>
      val total = recallInstances.size
      logger.info(s"rerank akka node size: $total")
      val distRecall = random.nextInt(total)
      val f = ask(recallInstances(distRecall), request).mapTo[RerankResponse]
      sender() ! f

    // 远程调用RerankServer
    case request: RerankRequest =>
      val total = rerankInstances.size
      logger.info(s"rerank akka node size: $total")
      val distRecall = random.nextInt(total)
      val f = ask(rerankInstances(distRecall), request).mapTo[RerankResponse]
      sender() ! f

    // 注册上recall节点
    case ServerRegistration("recall") if ! recallInstances.contains(sender()) =>
      logger.info(s"register recall node ${sender()}.")
      context watch sender()
      recallInstances = recallInstances :+ sender()

    // 注册上rerank节点
    case ServerRegistration("rerank") if ! rerankInstances.contains(sender()) =>
      logger.info(s"register rerank node ${sender()}.")
      context watch sender()
      rerankInstances = rerankInstances :+ sender()

    // 有节点下线通知，需要删除本地引用
    case Terminated(a) =>
      logger.info(s"terminated node $a")
      rerankInstances = rerankInstances.filterNot(_ == a)
      recallInstances = recallInstances.filterNot(_ == a)

    // 本服务启动，通知所有其他节点
    case MemberUp(member) =>
      logger.info(s"Member is Up: ${member.address}")
      notify(member)

    case UnreachableMember(member) =>
      logger.info(s"Member detected as unreachable: $member")

    // 服务启动时会通知所有节点
    case state: CurrentClusterState =>
      logger.info("case state: CurrentClusterState.")
      state.members.filter(_.status == MemberStatus.Up) foreach notify

    case MemberRemoved(member, previousStatus) =>
      logger.info(s"Member is Removed: ${member.address} after $previousStatus")

    case _: MemberEvent => // ignore

    case msg: Any =>
      logger.info(s"receive unknown actor message [ $msg ].")
  }

  def notify(member: Member): Unit = {
    logger.info(s"send engine start notify message to ${member.address} with role ${member.roles}.")
    val address = RootActorPath(member.address) / "user"
    logger.info(s"address: $address")
    if (member.hasRole("engine")) {
      context.actorSelection(address / "engine") ! ServerRegistration("engine")
    } else if (member.hasRole("recall")) {
      context.actorSelection(address / "recall") ! ServerRegistration("engine")
    } else if (member.hasRole("rerank")) {
      context.actorSelection(address / "rerank") ! ServerRegistration("engine")
    }

  }

}


object EngineServer extends Logging {
  def initAkkaCompone(): Unit = {
    val config = ConfigFactory
      .parseString("akka.cluster.roles = [engine]")
      .withFallback(AkkaContext.conf)
    AkkaContext.initActorSystem(Option(config))

    val system = AkkaContext.getSystem

    val zkS = ZookeeperClusterSeed(system)

    zkS.join()

  }

  def main(args: Array[String]): Unit = {
    EngineServer.initAkkaCompone()

    val system = AkkaContext.getSystem

    val engine = AkkaContext.getRouter("engine", classOf[EngineServer], "my-dispatcher")
    import scala.concurrent.duration._

    import system.dispatcher
    system.scheduler.schedule(5.seconds, 5.seconds) {
      engine ! TestMsg("test-msg")
    }
  }
}
