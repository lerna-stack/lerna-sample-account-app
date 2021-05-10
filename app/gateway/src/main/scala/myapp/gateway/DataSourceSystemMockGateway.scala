package myapp.gateway

import com.github.tomakehurst.wiremock.client.WireMock

class DataSourceSystemMockGateway(config: GatewayConfig) {

  private[this] val wireMock = WireMock
    .create()
    .host(config.dataSourceSystemHost)
    .port(config.dataSourceSystemPort)
    .build()

  def start(): Unit = {
    import WireMock._
    wireMock.resetMappings()
    wireMock.register(
      get("/test").willReturn(
        aResponse()
          .withHeader("Content-Type", "test/plain")
          .withBody("Hello"),
      ),
    )
  }
}
