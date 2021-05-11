package myapp.gateway

import com.github.tomakehurst.wiremock.client.WireMock
import scala.jdk.CollectionConverters._

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
      get(urlPathEqualTo("/data"))
        .atPriority(2)
        .withQueryParam("cursor", matching("(0|[1-9][0-9]*)"))
        .withQueryParam("limit", matching("[1-9][0-9]*"))
        .willReturn(
          ok()
            .withBody {
              """
              {
                 "data": [
                    {{#each parameters.numbers ~}}
                    { "cursor": {{@index}}, "accountNo": "{{randomValue length=10 type='NUMERIC'}}", "amount": 1000 }{{#unless @last}},{{/unless}}
                    {{/each}}
                 ]
              }
              """
            }.withTransformerParameter("numbers", (1 to 1000).asJava),
        ),
    )
    wireMock.register(
      get(urlPathEqualTo("/data"))
        .atPriority(1)
        .withQueryParam("cursor", matching("1000"))
        .withQueryParam("limit", matching("[1-9][0-9]*"))
        .willReturn(
          okJson {
            """
            {
               "data": [
               ]
            }
            """
          },
        ),
    )
  }
}
