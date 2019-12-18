package com.zjw.context

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.routing.RoundRobinPool
import com.typesafe.config.{Config, ConfigFactory}
import com.zjw.util.CommonUtil
import org.apache.logging.log4j.scala.Logging

/**
 * @author zjw <cn.zjwblog@gmail.com> 2019/12/16 下午8:13
 *
 */
object AkkaContext extends Logging {

  private var _name: String = _

  def initActorSystem(config: Option[Config]): Unit = {
    initActorSystem(name = s"RsServer", config)
  }

  def initActorSystem(name: String, config: Option[Config] = None): Unit = {
    synchronized {
      if (_system == null) {
        _name = name
        if (config.isDefined) {
          _system = ActorSystem.create(name, config.get)
        } else {
          _system = ActorSystem.create(name)
        }
      }
    }
  }

  def getRouter[T <: Actor](name: String, actorClass: Class[T], dispatcherName: String, args: Any*): ActorRef = {
    synchronized {
      val routerOp = _routerRegister.get(name)
      if (routerOp.isEmpty) {
        val router = _system.actorOf(
          RoundRobinPool(100).props(Props(actorClass, args: _*)).withDispatcher(dispatcherName),
          name
        )
        registerRouter(name, router)
        router
      } else {
        routerOp.get
      }
    }
  }

  def getSystem: ActorSystem = _system

  private def registerRouter(name: String, router: ActorRef): Unit = {
    _routerRegister = _routerRegister + (name -> router)
  }

  private[this] var _system: ActorSystem = _

  private[this] var _routerRegister: Map[String, ActorRef] = Map.empty

  private[this] val confStr =
    s"""
       |akka {
       |  actor {
       |    provider = cluster
       |    # allow-java-serialization = off
       |  }
       |
       |  # For the sample, just bind to loopback and do not allow access from the network
       |  # the port is overridden by the logic in main class
       |  remote.artery {
       |    enabled = on
       |    transport = tcp
       |    canonical.port = ${CommonUtil.randomPort(65000,65500)} # port range from 65000 to 65500
       |    canonical.hostname = ${CommonUtil.localIpAddress.getHostAddress}
       |  }
       |
       |  cluster {
       |    auto-down-unreachable-after = 10s
       |    seed.zookeeper {
       |      url = "127.0.0.1:2181"
       |      path = "/rs-distribute/akka/cluster/seed"
       |      auto-down {
       |        enabled = false
       |        wait-for-leader = 5 seconds
       |        unresolved-strategy = log
       |      }
       |    }
       |  }
       |}
       |
       |my-dispatcher {
       |  type = Dispatcher
       |  executor = "fork-join-executor"
       |  fork-join-executor {
       |    parallelism-min = 2 # Minimum threads
       |    parallelism-factor = 2.0 # Maximum threads per core
       |    parallelism-max = 10 # Maximum total threads
       |  }
       |  throughput = 100 # Max messages to process in an actor before moving on.
       |}
    """.stripMargin

  lazy val conf: Config = ConfigFactory.parseString(confStr)


}

case class ServerRegistration(tpe: String)

case class TestMsg(msg: String)

