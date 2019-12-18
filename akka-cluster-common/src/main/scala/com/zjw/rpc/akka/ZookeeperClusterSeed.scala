package com.zjw.rpc.akka

import java.io.Closeable

import scala.annotation.tailrec
import scala.collection.{immutable, mutable}
import scala.collection.JavaConverters._
import scala.concurrent.{Await, Promise, TimeoutException}
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.Exception.ignoring

import akka.actor.{ActorSystem, Address, AddressFromURIString, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.cluster.Cluster
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.cache.{PathChildrenCache, PathChildrenCacheEvent, PathChildrenCacheListener}
import org.apache.curator.framework.recipes.leader.{LeaderLatch, LeaderLatchListener}
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.logging.log4j.scala.Logging
import org.apache.zookeeper.KeeperException.{NodeExistsException, NoNodeException}

/**
 * @author zjw <cn.zjwblog@gmail.com> 2019/12/17 上午10:39
 *
 */
object ZookeeperClusterSeed extends ExtensionId[ZookeeperClusterSeed] with ExtensionIdProvider {
  var zookeeperClusterZeed: ZookeeperClusterSeed = _

  override def get(system: ActorSystem): ZookeeperClusterSeed = super.get(system)

  override def createExtension(system: ExtendedActorSystem): ZookeeperClusterSeed = {
    zookeeperClusterZeed = new ZookeeperClusterSeed(system)
    zookeeperClusterZeed
  }

  override def lookup(): ExtensionId[_ <: Extension] = ZookeeperClusterSeed
}

class ZookeeperClusterSeed(system: ExtendedActorSystem) extends Extension with Logging {

  val settings = new ZookeeperClusterSeedSettings(system)

  val clusterSystem = Cluster(system)
  val selfAddress: Address = clusterSystem.selfAddress
  val address: Address = if (settings.host.nonEmpty && settings.port.nonEmpty) {
    logger.info(s"host:port read from environment variables=${settings.host}:${settings.port}")
    selfAddress.copy(host = settings.host, port = settings.port)
  } else {
    Cluster(system).selfAddress
  }

  val client = AkkaCuratorClient(settings)

  val myId = s"${address.protocol}://${address.hostPort}"

  val path = s"${settings.ZKPath}/${system.name}"

  removeEphemeralNodes()

  private val latch = new LeaderLatch(client, path, myId)
  latch.addListener(new LeaderLatchListener {
    override def isLeader: Unit =
      logger.info("component=zookeeper-cluster-seed at=leader-change status=Became a leader")

    override def notLeader(): Unit =
      logger.info("component=zookeeper-cluster-seed at=leader-change status=Lost leadership")
  })

  if (settings.autoShutdown) {
    client.getConnectionStateListenable.addListener(new ConnectionStateListener {
      override def stateChanged(client: CuratorFramework, newState: ConnectionState): Unit = {
        import ConnectionState._
        newState match {
          case LOST =>
            clusterSystem.leave(clusterSystem.selfAddress)
            system.terminate()
          case stat: Any =>
            logger.info(s"receive zookeeper connection state ${stat}.")
        }
        logger.warn(
          s"component=zookeeper-cluser-seed at=conntection-state-changed state=${newState}"
        )
      }
    })

    clusterSystem.registerOnMemberRemoved {
      // exit JVM when ActorSystem has been terminated
      system.registerOnTermination(System.exit(0))
      // shut down ActorSystem
      system.terminate()
      system.log.info("Termination started")

      // In case ActorSystem shutdown takes longer than 10 seconds,
      // exit the JVM forcefully anyway.
      // We must spawn a separate thread to not block current thread,
      // since that would have blocked the shutdown of the ActorSystem.
      new Thread {
        override def run(): Unit = {
          if (Try(Await.ready(system.whenTerminated, 10.seconds)).isFailure) {
            logger.info("Forcing system exit")
            System.exit(-1)
          }
        }
      }.start()
    }
  }

  private var seedEntryAdded = false

  private val closeableServices = mutable.Set[Closeable]()

  closeableServices.add(latch)

  if (settings.autoDown) {

    @tailrec
    def waitForLeaderChange(times: Int, delay: Int)
                           (removedAddress: String): Either[Unit, Unit] =
      latch.getLeader.getId.equals(removedAddress) match {
        case false => Left(null)
        case _ if times > 0 =>
          Thread.sleep(delay)
          waitForLeaderChange(times - 1, delay)(removedAddress)
        case _ => Right(null)
      }

    val pathCache = new PathChildrenCache(client, path, true)
    pathCache.getListenable.addListener(new PathChildrenCacheListener {
      override def childEvent(client: CuratorFramework, event: PathChildrenCacheEvent): Unit = {
        event.getType match {
          case PathChildrenCacheEvent.Type.CHILD_REMOVED =>
            val childAddress = new String(event.getData.getData)
            waitForLeaderChange(settings.autoDownMaxWait.toMillis.toInt / 100, 100)(childAddress)
            match {
              case Left(_) =>
                // hasLeadership might not work yet, as it is a cached local field
                if (latch.getLeader.getId.equals(latch.getId)) {
                  logger.info(s"component=zookeeper-cluster-seed at=downing-cluster-node " +
                    s"node-address=${childAddress}")
                  clusterSystem.down(AddressFromURIString(childAddress))
                } else {
                  logger.info(s"component=zookeeper-cluster-seed at=downing-cluster-node" +
                    s" status=not a leader state=${latch.getState} leader=${latch.getLeader}")
                }
              case Right(_) => settings.autoDownUnresolvedStrategy match {
                case AutoDownUnresolvedStrategies.Log =>
                  logger.info("component=zookeeper-cluster-seed" +
                    " at=downing-cluster-node status=leader-change-timeout" +
                    " timed out while waiting for leader to change. Ignoring.")
                case AutoDownUnresolvedStrategies.ForceDown =>
                  logger.info("component=zookeeper-cluster-seed" +
                    " at=downing-cluster-node status=leader-change-timeout timed out " +
                    "while waiting for leader to change. Forcing down of ${childAddress}.")
                  clusterSystem.down(AddressFromURIString(childAddress))
                case _ => // ignore - validate in ZookeeperClusterSeedSettings
              }
            }
          case _ => // ignore non-child-removed events
        }
      }
    })
    pathCache.start()
    closeableServices.add(pathCache)
  }

  new Thread(new Runnable {
    override def run(): Unit = {
      Thread.sleep(5000)
      var isConnected = true
      var start = -1L
      while (true) {
        if (client != null && !client.getZookeeperClient.isConnected) {
          isConnected = false
          if (start == -1) {
            start = System.currentTimeMillis()
          }
        } else {
          isConnected = true
          start = -1
        }
        if (start != -1 && (System.currentTimeMillis() - start) > 3 * 60 * 1000) {
          new Thread(
            new Runnable {
              override def run(): Unit = {
                Thread.sleep(1000)
                System.exit(-1)
              }
            }
          ).start
          throw new RuntimeException(
            "system will exit after 1 second with code -1, due to zookeeper connection lost.")
        }
        Thread.sleep(1000)
      }
    }
  }).start()

  /**
   * Join or create a cluster using Zookeeper to handle
   */
  def join(): Unit = synchronized {
    createPathIfNeeded()
    latch.start()
    seedEntryAdded = true
    while (!tryJoin()) {
      logger.warn(s"component=zookeeper-cluster-seed at=try-join-failed id=${myId}")
      Thread.sleep(1000)
    }

    clusterSystem.registerOnMemberRemoved {
      removeSeedEntry()
    }

    system.registerOnTermination {
      ignoring(classOf[IllegalStateException]) {
        client.close()
      }
    }
  }

  def removeSeedEntry(): Unit = synchronized {
    if (seedEntryAdded) {
      closeableServices.foreach({
        s =>
          try {
            s.close()
          } catch {
            case e: Exception =>
          }
      })
      seedEntryAdded = false
    }
  }

  def isLeader(): Boolean = latch.hasLeadership

  private def tryJoin(): Boolean = {
    val leadParticipant = latch.getLeader
    if (!leadParticipant.isLeader) {
      false
    } else if (leadParticipant.getId == myId) {
      logger.info(s"component=zookeeper-cluster-seed at=this-node-is-leader-seed id=${myId}")
      Cluster(system).join(address)
      true
    } else {
      val seeds = latch.getParticipants.iterator().asScala.filterNot(_.getId == myId).map {
        node => AddressFromURIString(node.getId)
      }.toList
      logger.info(s"component=zookeeper-cluster-seed at=join-cluster seeds=${seeds}")
      Cluster(system).joinSeedNodes(immutable.Seq(seeds: _*))

      val joined = Promise[Boolean]()

      Cluster(system).registerOnMemberUp {
        joined.trySuccess(true)
      }

      try {
        Await.result(joined.future, 10.seconds)
      } catch {
        case _: TimeoutException => false
      }
    }
  }

  private def createPathIfNeeded() {
    Option(client.checkExists().forPath(path)).getOrElse {
      try {
        client.create().creatingParentsIfNeeded().forPath(path)
      } catch {
        case e: NodeExistsException =>
          logger.info("component=zookeeper-cluster-seed at=path-create-race-detected")
      }
    }
  }

  /**
   * Removes ephemeral nodes for self address that may exist when node restarts abnormally
   */
  def removeEphemeralNodes(): Unit = {
    val ephemeralNodes = try {
      client.getChildren.forPath(path).asScala
    } catch {
      case _: NoNodeException => Nil
    }

    ephemeralNodes
      .map(p => s"$path/$p")
      .map { p =>
        try {
          (p, client.getData.forPath(p))
        } catch {
          case _: NoNodeException => (p, Array.empty[Byte])
        }
      }
      .filter(pd => new String(pd._2) == myId)
      .foreach {
        case (p, _) =>
          try {
            client.delete.forPath(p)
          } catch {
            case _: NoNodeException => // do nothing
          }
      }
  }
}