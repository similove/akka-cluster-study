package com.zjw.rpc.akka

import scala.concurrent.duration.Duration
import scala.util.Try

import akka.actor.ActorSystem
import com.typesafe.config.Config
import org.apache.logging.log4j.scala.Logging

/**
 * @author zjw <cn.zjwblog@gmail.com> 2019/12/17 上午10:40
 *
 */
class ZookeeperClusterSeedSettings(
                                    system: ActorSystem,
                                    settingsRoot: String = "akka.cluster.seed.zookeeper",
                                    overwrittenActorSettings: Option[Config] = None
                                  ) extends Logging {

  private val zc = overwrittenActorSettings.getOrElse(system.settings.config)
    .getConfig(settingsRoot)

  val ZKUrl = zc.getString("url")

  val ZKPath: String = zc.getString("path")

  val ZKAuthorization: Option[(String, String)] = {
    if (zc.hasPath("authorization.scheme") && zc.hasPath("authorization.auth")) {
      Some((zc.getString("authorization.scheme"), zc.getString("authorization.auth")))
    } else {
      None
    }
  }

  val host: Option[String] = if (zc.hasPath("host_env_var")) {
    Some(zc.getString("host_env_var"))
  } else {
    None
  }

  val port: Option[Int] = if (zc.hasPath("port_env_var")) {
    Some(zc.getInt("port_env_var"))
  } else {
    None
  }

  val autoDown: Boolean = Try(zc.getBoolean("auto-down.enabled")).getOrElse(false)

  val autoDownMaxWait: Duration =
    Try(Duration(zc.getString("auto-down.wait-for-leader"))).getOrElse(Duration("5 seconds"))

  val autoDownUnresolvedStrategy: String = {
    Try(zc.getString("auto-down.unresolved-strategy")).map { strategy =>
      if (!strategy.equals(AutoDownUnresolvedStrategies.Log)
        && !strategy.equals(AutoDownUnresolvedStrategies.ForceDown)) {
        logger.warn("component=zookeeper-cluster-settings at=config-resolve auto-down." +
          s"unresolved-strateg uses unrecognised value {} while the valid values are " +
          s"[${AutoDownUnresolvedStrategies.ForceDown}, ${AutoDownUnresolvedStrategies.Log}]. " +
          s"Defaulting to ${AutoDownUnresolvedStrategies.Log}")
        AutoDownUnresolvedStrategies.Log
      } else strategy
    }.getOrElse(AutoDownUnresolvedStrategies.Log)

  }

  val autoShutdown: Boolean = Try(zc.getBoolean("shutdown-on-disconnect")).getOrElse(false)
}
