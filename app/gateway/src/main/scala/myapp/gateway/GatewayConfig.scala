package myapp.gateway

import com.typesafe.config.Config

class GatewayConfig(root: Config) {

  private[this] val gateway: Config = root.getConfig("myapp.gateway")

  private[this] val dataSourceSystemConfig: Config = gateway.getConfig("data-source-system")

  val dataSourceSystemHost: String = dataSourceSystemConfig.getString("host")
  val dataSourceSystemPort: Int    = dataSourceSystemConfig.getInt("port")
}
