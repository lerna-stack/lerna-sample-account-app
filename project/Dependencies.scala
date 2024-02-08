import sbt._

object Dependencies {

  object Versions {
    val akkaEntityReplication    = "2.2.0"
    val lerna                    = "3.0.1"
    val akka                     = "2.6.17"
    val akkaHttp                 = "10.2.4"
    val akkaPersistenceCassandra = "1.0.1"
    val akkaProjection           = "1.1.0"
    val scalaTest                = "3.1.4"
    val airframe                 = "20.9.0"
    val logback                  = "1.2.3"
    val slick                    = "3.3.3"
    val expecty                  = "0.14.1"
    val janino                   = "3.0.16"
    val h2                       = "1.4.200"
    val mariadbConnectorJ        = "2.6.2"
    val sprayJson                = "1.3.5"
    val jackson                  = "2.12.3"
    val wireMock                 = "2.30.1"
    val scalaMock                = "5.1.0"
    val akkaManagement           = "1.0.10"
    val pekko = "1.0.2"
  }

  object Lerna {
    val akkaEntityReplication = "com.lerna-stack" %% "akka-entity-replication" % Versions.akkaEntityReplication
    val http                  = "com.lerna-stack" %% "lerna-http"              % Versions.lerna
    val log                   = "com.lerna-stack" %% "lerna-log"               % Versions.lerna
    val management            = "com.lerna-stack" %% "lerna-management"        % Versions.lerna
    val testkit               = "com.lerna-stack" %% "lerna-testkit"           % Versions.lerna
    val util                  = "com.lerna-stack" %% "lerna-util"              % Versions.lerna
    val utilAkka              = "com.lerna-stack" %% "lerna-util-akka"         % Versions.lerna
    val utilSequence          = "com.lerna-stack" %% "lerna-util-sequence"     % Versions.lerna
    val validation            = "com.lerna-stack" %% "lerna-validation"        % Versions.lerna
    val wartCore              = "com.lerna-stack" %% "lerna-wart-core"         % Versions.lerna
  }

  object Akka {
    val actor                = "com.typesafe.akka" %% "akka-actor-typed"            % Versions.akka
    val stream               = "com.typesafe.akka" %% "akka-stream"                 % Versions.akka
    val cluster              = "com.typesafe.akka" %% "akka-cluster-typed"          % Versions.akka
    val clusterSharding      = "com.typesafe.akka" %% "akka-cluster-sharding-typed" % Versions.akka
    val clusterTools         = "com.typesafe.akka" %% "akka-cluster-tools"          % Versions.akka
    val persistence          = "com.typesafe.akka" %% "akka-persistence-typed"      % Versions.akka
    val persistenceQuery     = "com.typesafe.akka" %% "akka-persistence-query"      % Versions.akka
    val actorTestKit         = "com.typesafe.akka" %% "akka-actor-testkit-typed"    % Versions.akka
    val serializationJackson = "com.typesafe.akka" %% "akka-serialization-jackson"  % Versions.akka
    val streamTestKit        = "com.typesafe.akka" %% "akka-stream-testkit"         % Versions.akka
    val multiNodeTestKit     = "com.typesafe.akka" %% "akka-multi-node-testkit"     % Versions.akka
    val persistenceTestKit   = "com.typesafe.akka" %% "akka-persistence-testkit"    % Versions.akka
    val discovery            = "com.typesafe.akka" %% "akka-discovery"              % Versions.akka
  }

  object Pekko {
    val actor = "org.apache.pekko" %% "pekko-actor-typed" % Versions.pekko
    val actorTestKit = "org.apache.pekko" %% "pekko-actor-testkit-typed" % Versions.pekko
    val serializationJackson = "org.apache.pekko" %% "pekko-serialization-jackson" % Versions.pekko
  }

  object AkkaHttp {
    val http        = "com.typesafe.akka" %% "akka-http"            % Versions.akkaHttp
    val sprayJson   = "com.typesafe.akka" %% "akka-http-spray-json" % Versions.akkaHttp
    val httpTestKit = "com.typesafe.akka" %% "akka-http-testkit"    % Versions.akkaHttp
  }

  object AkkaManagement {
    val management = "com.lightbend.akka.management" %% "akka-management" % Versions.akkaManagement
    val clusterBootstrap =
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % Versions.akkaManagement
  }

  object AkkaPersistenceCassandra {
    val akkaPersistenceCassandra =
      "com.typesafe.akka" %% "akka-persistence-cassandra" % Versions.akkaPersistenceCassandra
  }

  object AkkaProjection {
    val eventsourced = "com.lightbend.akka" %% "akka-projection-eventsourced" % Versions.akkaProjection
    val slick        = "com.lightbend.akka" %% "akka-projection-slick"        % Versions.akkaProjection
    val testKit      = "com.lightbend.akka" %% "akka-projection-testkit"      % Versions.akkaProjection
  }

  object ScalaTest {
    val scalaTest = "org.scalatest" %% "scalatest" % Versions.scalaTest
  }

  object Airframe {
    val airframe = "org.wvlet.airframe" %% "airframe" % Versions.airframe
  }

  object SprayJson {
    val sprayJson = "io.spray" %% "spray-json" % Versions.sprayJson
  }

  object Logback {
    val logback = "ch.qos.logback" % "logback-classic" % Versions.logback
  }

  object Slick {
    val slick    = "com.typesafe.slick" %% "slick"          % Versions.slick
    val codegen  = "com.typesafe.slick" %% "slick-codegen"  % Versions.slick
    val hikaricp = "com.typesafe.slick" %% "slick-hikaricp" % Versions.slick
  }

  object Expecty {
    val expecty = "com.eed3si9n.expecty" %% "expecty" % Versions.expecty
  }

  object Janino {
    val janino = "org.codehaus.janino" % "janino" % Versions.janino
  }

  object H2 {
    val h2 = "com.h2database" % "h2" % Versions.h2
  }

  object MariaDB {
    val connectorJ = "org.mariadb.jdbc" % "mariadb-java-client" % Versions.mariadbConnectorJ
  }

  object WireMock {
    val wireMock = "com.github.tomakehurst" % "wiremock-jre8" % Versions.wireMock
  }

  object ScalaMock {
    val scalaMock = "org.scalamock" %% "scalamock" % Versions.scalaMock
  }

  // NOTE
  // 依存しているライブラリによって、要求するバージョンが異なる(`2.11.0` と `2.10.5`)。
  // 1つのバージョンに固定する必要があるため、明示的に Jackson の依存を宣言する必要がある。
  // Jackson の Minor バージョン には後方互換性があるため、メジャーバージョンが同一の最新のマイナーバージョンを指定すればよい。
  // https://github.com/FasterXML/jackson-docs#on-jackson-versioning
  object Jackson {
    lazy val all =
      Seq(core, annotations, databind, dataFormatCbor, datatypeJDK8, datatypeJSR310, moduleScala, moduleParameterNames)
    val core                 = "com.fasterxml.jackson.core"       % "jackson-core"                   % Versions.jackson
    val annotations          = "com.fasterxml.jackson.core"       % "jackson-annotations"            % Versions.jackson
    val databind             = "com.fasterxml.jackson.core"       % "jackson-databind"               % Versions.jackson
    val dataFormatCbor       = "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor"        % Versions.jackson
    val datatypeJDK8         = "com.fasterxml.jackson.datatype"   % "jackson-datatype-jdk8"          % Versions.jackson
    val datatypeJSR310       = "com.fasterxml.jackson.datatype"   % "jackson-datatype-jsr310"        % Versions.jackson
    val moduleScala          = "com.fasterxml.jackson.module"    %% "jackson-module-scala"           % Versions.jackson
    val moduleParameterNames = "com.fasterxml.jackson.module"     % "jackson-module-parameter-names" % Versions.jackson
  }

}
