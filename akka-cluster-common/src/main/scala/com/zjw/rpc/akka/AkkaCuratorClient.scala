package com.zjw.rpc.akka

import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.RetryNTimes
import org.apache.curator.test.TestingServer

object AkkaCuratorClient {
  def apply(settings: ZookeeperClusterSeedSettings): CuratorFramework = {
    val retryPolicy = new RetryNTimes(15, 5000)
    val connStr = settings.ZKUrl.replace("zk://", "")
    val curatorBuilder = CuratorFrameworkFactory.builder()
      .connectString(connStr)
      .retryPolicy(retryPolicy)

    settings.ZKAuthorization match {
      case Some((scheme, auth)) =>
        curatorBuilder.authorization(scheme, auth.getBytes)
      case None =>
    }

    val client = curatorBuilder.build()

    client.start()
    client.blockUntilConnected()
    client
  }

  def apply(): CuratorFramework = {
    val server = new TestingServer
    server.start()
    val client = CuratorFrameworkFactory.newClient(
      server.getConnectString,
      60 * 1000,
      30 * 1000,
      new RetryNTimes(3, 1000)
    )
    client.start()
    client
  }
}