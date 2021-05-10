import Helper._
import Dependencies._
import scala.util.Try

ThisBuild / name := "myapp"
ThisBuild / description := "description"
ThisBuild / version := "1.0.0"
ThisBuild / organization := "organization"
ThisBuild / scalaVersion := "2.12.12"
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlint",
)
ThisBuild / scalacOptions ++=
  sys.env.get("SBT_SCALAC_STRICT_WARNINGS").filter(_ == "true").map(_ => "-Xfatal-warnings").toSeq
ThisBuild / javaOptions in run ++= distinctJavaOptions(
  // fork先にはシステムプロパティが引き継がれないため
  sbtJavaOptions,
  Seq(
    // ローカル開発環境でのみ有効にしたい環境変数はここで指定する。
    "-Dmyapp.server-mode=DEV",
    "-Dakka.persistence.cassandra.journal.keyspace-autocreate=true",
    "-Dakka.persistence.cassandra.journal.tables-autocreate=true",
  ),
)
// fork先にはシステムプロパティが引き継がれないため
ThisBuild / javaOptions in Test ++= sbtJavaOptions
// CoordinatedShutdown の testのため・フォークするとデバッガが接続できなくなるため
ThisBuild / fork in run :=
  sys.props.get("fork").flatMap(s => Try(s.toBoolean).toOption).getOrElse(!isDebugging)
// ~test で繰り返しテストできるようにするため
ThisBuild / fork in Test := true
// forkプロセスのstdoutをこのプロセスのstdout,stderrをこのプロセスのstderrに転送する
// デフォルトのLoggedOutputでは、airframeやkamonが標準エラーに出力するログが[error]とプリフィクスがつき紛らわしいため
ThisBuild / outputStrategy := Some(StdoutOutput)

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging, JavaServerAppPackaging, RpmPlugin, SystemdPlugin)
  .aggregate(
    `presentation`,
    `gateway`,
    `adapter`,
    `application`,
    `read-model`,
    `utility`,
    `entrypoint`,
    `testkit`,
  )
  .dependsOn(`presentation`, `gateway`, `adapter`, `application`, `read-model`, `utility`, `entrypoint`)
  .settings(rpmPackageSettings)

lazy val `presentation` = (project in file("app/presentation"))
  .dependsOn(
    `adapter`,
    `read-model`,
    `testkit` % Test,
    `utility`,
  )
  .settings(wartremoverSettings, coverageSettings)
  .settings(
    name := "presentation",
    libraryDependencies ++= Seq(
      Lerna.http,
      Lerna.validation,
      Lerna.management,
      Airframe.airframe,
      Akka.stream,
      AkkaHttp.http,
      AkkaHttp.sprayJson,
      Akka.testKit         % Test,
      AkkaHttp.httpTestKit % Test,
    ),
  )

lazy val `gateway` = (project in file("app/gateway"))
  .dependsOn(
    `adapter`,
    `utility`,
  )
  .settings(wartremoverSettings, coverageSettings)
  .settings(
    name := "gateway",
    libraryDependencies ++= Seq(
      Lerna.http,
      Airframe.airframe,
      Akka.actor,
      Akka.stream,
      AkkaHttp.http,
      AkkaHttp.sprayJson,
      Akka.testKit         % Test,
      AkkaHttp.httpTestKit % Test,
    ),
  )

lazy val `adapter` = (project in file("app/adapter"))
  .dependsOn(`utility`, `utility` % "test->test")
  .settings(wartremoverSettings, coverageSettings)
  .settings(
    name := "adapter",
    libraryDependencies ++= Seq(
      ScalaTest.scalaTest % Test,
    ),
  )

lazy val `application` = (project in file("app/application"))
  .dependsOn(
    `adapter`,
    `read-model`,
    `testkit` % Test,
    `utility`,
  )
  .settings(wartremoverSettings, coverageSettings)
  .settings(
    name := "application",
    libraryDependencies ++= Seq(
      Lerna.utilSequence,
      Lerna.utilAkka,
      Lerna.management,
      Airframe.airframe,
      Akka.actor,
      Akka.stream,
      Akka.persistence,
      Akka.cluster,
      Akka.clusterTools,
      Akka.clusterSharding,
      Akka.slf4j,
      Akka.persistenceQuery,
      AkkaPersistenceCassandra.akkaPersistenceCassandra,
      Kryo.kryo,
      SprayJson.sprayJson,
      Akka.testKit          % Test,
      Akka.multiNodeTestKit % Test,
      Akka.streamTestKit    % Test,
    ),
  )

lazy val `read-model` = (project in file("app/read-model"))
  .dependsOn(`utility`, `testkit` % "test")
  .settings(wartremoverSettings, coverageSettings)
  .settings(
    name := "read-model",
    libraryDependencies ++= Seq(
      Airframe.airframe,
      Slick.slick,
      Slick.hikaricp,
      MariaDB.connectorJ,
    ),
    scalacOptions -= "-Xlint",
  )

lazy val `utility` = (project in file("app/utility"))
  .dependsOn(`testkit` % "test")
  .settings(wartremoverSettings, coverageSettings)
  .settings(
    name := "utility",
    libraryDependencies ++= Seq(
      Lerna.util,
      Lerna.log,
      Airframe.airframe,
      // Logback and Janino is used for logging, utility has some logging configuration
      Logback.logback,
      Janino.janino,
      Expecty.expecty,
      ScalaTest.scalaTest % Test,
    ),
  )

lazy val `entrypoint` =
  (project in file("app/entrypoint"))
    .dependsOn(
      `presentation`,
      `gateway`,
      `adapter`,
      `application`,
      `read-model`,
      `utility`,
      `testkit` % "test",
    )
    .settings(wartremoverSettings, coverageSettings)
    .settings(
      name := "entrypoint",
      libraryDependencies ++= Seq(
        Airframe.airframe,
        ScalaTest.scalaTest % Test,
      ),
    )

lazy val `testkit` = (project in file("app/testkit"))
  .settings(wartremoverSettings, coverageSettings)
  .settings(
    name := "testkit",
    libraryDependencies ++= Seq(
      Lerna.util,
      Lerna.testkit,
      Expecty.expecty,
      ScalaTest.scalaTest,
      Airframe.airframe,
      WireMock.wireMock,
    ),
  )

lazy val `slick-codegen` = (project in file("slick-codegen"))
  .settings(wartremoverSettings, coverageSettings)
  .settings(
    name := "slick-codegen",
    libraryDependencies ++= Seq(
      Slick.codegen,
      MariaDB.connectorJ,
    ),
  )

lazy val wart = project // FIXME: Plugin化
  .settings(
    libraryDependencies ++= Seq(
      Lerna.wartCore,
    ),
  )

lazy val wartremoverSettings = Def.settings(
  wartremoverClasspaths ++= {
    (fullClasspath in (wart, Compile)).value.map(_.data.toURI.toString)
  },
  // Warts.Unsafe をベースにカスタマイズ
  wartremoverErrors in (Compile, compile) := Seq(
    // Wart.Any,                        // Warts.Unsafe: Akka の API で Any が使われるため
    Wart.AsInstanceOf,            // Warts.Unsafe
    Wart.EitherProjectionPartial, // Warts.Unsafe
    Wart.IsInstanceOf,            // Warts.Unsafe
    // Wart.NonUnitStatements,          // Warts.Unsafe: 誤検知が多く、回避しようとすると煩雑なコードが必要になる
    Wart.Null,          // Warts.Unsafe
    Wart.OptionPartial, // Warts.Unsafe
    Wart.Product,       // Warts.Unsafe
    Wart.Return,        // Warts.Unsafe
    Wart.Serializable,  // Warts.Unsafe
    Wart.StringPlusAny, // Warts.Unsafe
    // Wart.Throw,                      // Warts.Unsafe: Future を失敗させるときに使うことがある
    Wart.TraversableOps,         // Warts.Unsafe
    Wart.TryPartial,             // Warts.Unsafe
    Wart.Var,                    // Warts.Unsafe
    Wart.ArrayEquals,            // Array の比較は sameElements を使う
    Wart.AnyVal,                 // 異なる型のオブジェクトを List などに入れない
    Wart.Equals,                 // == の代わりに === を使う
    Wart.ExplicitImplicitTypes,  // implicit val には明示的に型を指定する
    Wart.FinalCaseClass,         // case class は継承しない
    Wart.JavaConversions,        // scala.collection.JavaConverters を使う
    Wart.OptionPartial,          // Option#get は使わずに fold などの代替を使う
    Wart.Recursion,              // 単純な再帰処理は使わずに末尾最適化して @tailrec を付けるかループ処理を使う
    Wart.TraversableOps,         // head の代わりに headOption など例外を出さないメソッドを使う
    Wart.TryPartial,             // Success と Failure の両方をハンドリングする
    ContribWart.MissingOverride, // ミックスインしたトレイトと同じメソッドやプロパティを宣言するときは必ず override をつける
    ContribWart.OldTime,         // Java 8 の新しい Date API を使う
    ContribWart.SomeApply,       // Some(...) の代わりに Option(...) を使う
    CustomWart.Awaits,
    CustomWart.CyclomaticComplexity,
    CustomWart.NamingClass,
    CustomWart.NamingDef,
    CustomWart.NamingObject,
    CustomWart.NamingPackage,
    CustomWart.NamingVal,
    CustomWart.NamingVar,
  ),
  wartremoverErrors in (Test, compile) := (wartremoverErrors in (Compile, compile)).value,
  wartremoverErrors in (Test, compile) --= Seq(
    CustomWart.CyclomaticComplexity,
  ),
)

lazy val coverageSettings = Def.settings(
  coverageMinimum := 80,
  coverageFailOnMinimum := false,
  // You can exclude classes from being considered for coverage measurement by
  // providing semicolon-separated list of regular expressions.
  coverageExcludedPackages := Seq(
    """myapp\.entrypoint\.Main\$?""",
    """myapp\.readmodel\.schema\.Tables.*""",
  ).mkString(";"),
)

// TODO Make each setting key `packageBin` scoped
lazy val rpmPackageSettings = Seq(
  name := sys.props.collectFirst { case ("project.name", v) => v }.getOrElse("app"),
  rpmRelease := "1",
  rpmVendor := "???",
  maintainer in Linux := "???",
  rpmUrl := Some("???"),
  rpmLicense := Some("???"),
  rpmAutoreq := "no",
  serverLoading in Rpm := Some(ServerLoader.Systemd),
  daemonUser := "app",
  daemonGroup := "app",
  retryTimeout in Rpm := 30,
  linuxPackageMappings in Rpm := configWithNoReplace((linuxPackageMappings in Rpm).value),
  linuxPackageSymlinks := Seq.empty,
  // インフラチームからの要請によりアプリ関連のファイルは全て /apl/ 配下に配置する
  defaultLinuxInstallLocation := "/apl",
  defaultLinuxLogsLocation := defaultLinuxInstallLocation.value + "/var/log",
  bashScriptEnvConfigLocation := Option(
    defaultLinuxInstallLocation.value + sys.props
      .collectFirst { case ("project.name", v) if v != "app" => "/etc/default2" }.getOrElse("/etc/default"),
  ),
  linuxPackageMappings in Rpm := {
    val mappings = (linuxPackageMappings in Rpm).value
    mappings.map { linuxPackage =>
      val filtered = linuxPackage.mappings.filterNot {
        // PID ファイルは使わないので
        case (_, mappingName) => mappingName.startsWith("/var/run/")
      }
      linuxPackage.copy(mappings = filtered)
    } ++ Seq(
      packageMapping(
        (baseDirectory.value / "CHANGELOG.md") -> (defaultLinuxInstallLocation.value + s"/${name.value}" + "/CHANGELOG.md"),
      ),
      // Kamon が起動時に /apl/${name}/native/libsigar-amd64-linux.so を書き込む
      packageTemplateMapping(
        defaultLinuxInstallLocation.value + s"/${name.value}" + "/native",
      )() withUser daemonUser.value withGroup daemonGroup.value,
    )
  },
  rpmRelease := sys.props.collectFirst { case ("rpm.release", v) => v }.getOrElse("1"),
  rpmPrefix := Option(defaultLinuxInstallLocation.value),
  mainClass in Compile := Some("myapp.entrypoint.Main"),
  javaOptions in Universal := Nil, // 注意： Terraform で application.ini を上書きするので javaOptions は定義しないこと。代わりに bashScriptExtraDefines を使う。
  bashScriptExtraDefines ++= Seq(
    s"""addJava "-Dmyapp.presentation.versions.version=${version.value}"""",
    s"""addJava "-Dmyapp.presentation.versions.commit-hash=${fetchGitCommitHash.value}"""",
  ),
  maintainerScripts in Rpm ++= {
    import RpmConstants._
    Map(
      Postun -> Seq(""), // デフォルトではサービスの再起動が行われるが、アップグレード後の任意のタイミングで再起動したいため
    )
  },
)

val fetchGitCommitHash = taskKey[String]("fetch git commit hash")
fetchGitCommitHash := {
  import scala.sys.process._
  "git rev-parse HEAD".!!.trim
}

addCommandAlias("take-test-coverage", "clean;coverage;test:compile;test;coverageReport;coverageAggregate;")
