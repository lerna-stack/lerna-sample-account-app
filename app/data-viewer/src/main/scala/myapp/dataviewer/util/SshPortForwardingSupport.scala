package myapp.dataviewer.util

import com.datastax.oss.driver.api.core.addresstranslation.AddressTranslator
import com.datastax.oss.driver.api.core.context.DriverContext

import java.net.{ InetAddress, InetSocketAddress }

/** Cassandra Cluster への接続を ssh port forwarding 経由でできるようにする AddressTranslator.
  *
  * Cassandra Cluster との接続は以下の流れで行われるため、 server(Cassandra Cluster) と client(app) で認識するIPが違う場合は、変換が必要
  * <li>1. 初回接続（どれか1 node）で、Cluster が認識している 自身のIP list をクライアントに伝える</li>
  * <li>2. クライアントは1 のIP list それぞれとTCP接続を確立する</li>
  *
  * 前提<br>
  * ssh port forwarding では以下のようにIPアドレスの第一オクテットを `127`` にするルールとする
  * <li>`10.1.2.3` -> `127.1.2.3`</li>
  * <li>`192.168.1.2` -> `127.168.1.2`</li>
  */
final case class SshPortForwardingSupport(driverContext: DriverContext) extends AddressTranslator {
  override def translate(address: InetSocketAddress): InetSocketAddress = {
    val newHost = address.getHostName.replaceAll("^[^.]+", "127")
    val newAddress = new InetSocketAddress(
      InetAddress.getByName(newHost),
      address.getPort,
    )
    newAddress
  }

  override def close(): Unit = {
    // do nothing
  }
}
