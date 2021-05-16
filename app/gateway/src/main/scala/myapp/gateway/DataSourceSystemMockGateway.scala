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
        .withQueryParam("cursor", matching("(0|[1-9][0-9]*)"))
        .withQueryParam("limit", matching("[1-9][0-9]*"))
        .willReturn(
          ok()
            .withHeader("Content-Type", "application/json")
            .withBody {
              """
              {{#assign 'limit'}}
                {{#if (compare request.query.cursor '<' 10000)}}
                  {{request.query.limit}}
                {{else}}
                  {{sub request.query.limit 1}}
                {{/if}}
              {{/assign}}
              {
                 "data": [
                    {{#each (seq from=(add request.query.cursor 1) count=limit) ~}}
                    { "cursor": "{{this}}", "accountNo": "{{randomValue length=1 type='NUMERIC'}}", "amount": 1000 }{{#unless @last}},{{/unless}}
                    {{/each}}
                 ]
              }
              """
            },
        ),
    )
    wireMock.register(
      get(urlPathEqualTo("/data"))
        .withQueryParam("cursor", absent())
        .withQueryParam("limit", matching("[1-9][0-9]*"))
        .willReturn(
          ok()
            .withHeader("Content-Type", "application/json")
            .withBody {
              """
              {
                 "data": [
                    {{#each (seq from=1 count=request.query.limit) ~}}
                    { "cursor": "{{this}}", "accountNo": "{{randomValue length=1 type='NUMERIC'}}", "amount": 1000 }{{#unless @last}},{{/unless}}
                    {{/each}}
                 ]
              }
              """
            },
        ),
    )
  }
}
