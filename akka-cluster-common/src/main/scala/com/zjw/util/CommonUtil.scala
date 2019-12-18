package com.zjw.util

import java.net.{Inet4Address, InetAddress, NetworkInterface, _}

import scala.collection.JavaConverters._
import scala.util.Random

import com.typesafe.config.ConfigFactory
import org.apache.logging.log4j.scala.Logging

/**
 * @author zjw <cn.zjwblog@gmail.com> 2019/12/17 上午9:47
 *
 */
object CommonUtil extends Logging {
  def decodeParameter(param: String): String = URLDecoder.decode(param)

  private[this] val random = new Random()
  lazy val localIpAddress: InetAddress = findLocalInetAddress()

  private[this] def findLocalInetAddress(): InetAddress = {
    val address = InetAddress.getLocalHost
    if (address.isLoopbackAddress) {

      val activeNetworkIFs = NetworkInterface.getNetworkInterfaces.asScala.toSeq

      for (ni <- activeNetworkIFs.reverse) {
        val addresses = ni.getInetAddresses.asScala
          .filterNot(addr => addr.isLinkLocalAddress || addr.isLoopbackAddress).toSeq
        if (addresses.nonEmpty) {
          val addr = addresses.find(_.isInstanceOf[Inet4Address]).getOrElse(addresses.head)
          val strippedAddress = InetAddress.getByAddress(addr.getAddress)
          logger.warn("Your hostname, " + InetAddress.getLocalHost.getHostName + " resolves to" +
            " a loopback address: " + address.getHostAddress + "; using " +
            strippedAddress.getHostAddress + " instead (on interface " + ni.getName + ")")
          logger.warn("Set MERLION_LOCAL_IP if you need to bind to another address")
          return strippedAddress
        }
      }
      logger.warn("Your hostname, " + InetAddress.getLocalHost.getHostName + " resolves to" +
        " a loopback address: " + address.getHostAddress + ", but we couldn't find any" +
        " external IP address!")
      logger.warn("Set MERLION_LOCAL_IP if you need to bind to another address")
    }
    address
  }

  def randomPort(min: Int, max: Int): Int = random.nextInt(max - min) + min
}
