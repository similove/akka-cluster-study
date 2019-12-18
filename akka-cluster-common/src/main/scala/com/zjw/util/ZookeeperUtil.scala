package com.zjw.util

import scala.collection.JavaConverters._
import scala.collection.mutable.Buffer

import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.framework.recipes.cache.{PathChildrenCache, PathChildrenCacheListener, TreeCache, TreeCacheListener}
import org.apache.curator.framework.recipes.cache.PathChildrenCache.StartMode
import org.apache.curator.retry.RetryNTimes
import org.apache.zookeeper.CreateMode

/**
 * @author zjw <cn.zjwblog@gmail.com> 2019/12/17 上午10:33
 *
 */
object ZookeeperUtil {
  private[this] val address = "127.0.0.1:2181"


  private[this] var _client: CuratorFramework = _

  def getClient: CuratorFramework = {
    if (_client == null) {
      _client = CuratorFrameworkFactory.newClient(
        address,
        new RetryNTimes(10, 5000)
      )
      _client.start()
    }
    _client
  }

  def setOrUpdate(
                   path: String,
                   bytes: Array[Byte],
                   createMode: CreateMode = CreateMode.PERSISTENT
                 ): Unit = {
    if (getClient.checkExists().forPath(path) == null) {
      // create znode
      getClient.create()
        .creatingParentsIfNeeded()
        .withMode(createMode)
        .forPath(path, bytes)
    } else {
      // update znode
      getClient.setData()
        .forPath(path, bytes)
    }
  }

  /**
   * 添加znode path
   *
   * @param path
   * @param bytes
   * @param createMode
   *
   * @throws java.lang.Exception
   */
  @throws(classOf[Exception])
  def addNode(
               path: String,
               bytes: Array[Byte],
               createMode: CreateMode = CreateMode.PERSISTENT
             ): Unit = {
    // create znode
    getClient.create()
      .creatingParentsIfNeeded()
      .withMode(createMode)
      .forPath(path, bytes)
  }

  def getData(path: String): Array[Byte] = {
    getClient.getData.forPath(path)
  }

  def listChildren(path: String): Buffer[String] = {
    getClient.getChildren.forPath(path).asScala
  }

  /**
   * 强制删除path
   *
   * @param path path to delete.
   */
  def removeZNode(path: String): Unit = {
    getClient.delete().guaranteed().deletingChildrenIfNeeded().forPath(path)
  }

  def listHistoryVersionData(): Unit = {

  }

  /**
   * 添加监控子节点的zk watcher
   *
   * @param path
   * @param listener
   * @param startMode
   */
  def addPathChildrenCacheWatcher(
                                   path: String,
                                   listener: PathChildrenCacheListener,
                                   startMode: StartMode = StartMode.POST_INITIALIZED_EVENT
                                 ): PathChildrenCache = {
    val watcher = new PathChildrenCache(
      getClient,
      path,
      true
    )
    watcher.getListenable.addListener(listener)
    watcher.start(startMode)
    watcher
  }

  def addTreeCacheWatcher(
                           path: String,
                           listener: TreeCacheListener
                         ): TreeCache = {
    val watcher = new TreeCache(getClient, path)
    watcher.getListenable.addListener(listener)
    watcher.start()
    watcher
  }

}
