package com.zjw.config

import java.io.File
import java.nio.file.Paths

import com.typesafe.config.ConfigFactory
import org.apache.logging.log4j.scala.Logging

/**
 * @author zjw <cn.zjwblog@gmail.com> 2019/12/16 下午8:54
 *
 */
object Config extends Logging {
  private[this] var _confMap: Array[Product] = Array.empty

  private[this] val configPath = scala.util.Properties.envOrElse(
    "AKKA_CLUSTER_HOME",
    Paths.get("").toAbsolutePath.normalize.toString
  ) + "/conf"

  private[this] val mode = scala.util.Properties.envOrElse("MODE", "dev")

  private[this] val configFile = configPath + "/config." + mode

  private[this] val _config = ConfigFactory.parseFile(new File(configFile))

  val zookeeperQuorum: String = _config.getString("cluster.common.zookeeper.quorum")

  val commonTimeout: Int = getConf("cluster.common.timeout", _config.getInt)


  private def getConf[T](path: String, getOp: String => T, note: String = ""): T = {
    val c = getOp(path)
    registeConf(path, c.toString)
    c
  }

   private def registeConf(name: String, conf: String, note: String = ""): Unit = {
    _confMap = _confMap :+ (name, conf, note)
  }

  def getConfMap(): Array[Product] = {
    _confMap
  }

  def main(args: Array[String]): Unit = {
    println(zookeeperQuorum)
  }
}
