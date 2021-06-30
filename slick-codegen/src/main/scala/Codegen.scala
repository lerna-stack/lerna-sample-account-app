import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import slick.basic.DatabaseConfig
import slick.codegen.SourceCodeGenerator
import slick.jdbc.JdbcProfile

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success }
import scala.jdk.CollectionConverters._

@SuppressWarnings(
  Array(
    "lerna.warts.Awaits",
    "lerna.warts.NamingDef",
    "lerna.warts.CyclomaticComplexity",
  ),
)
object Codegen extends App {

  val log = LoggerFactory.getLogger(getClass)

  val config                         = ConfigFactory.load().getConfig("slick.codegen")
  val dbConfig                       = DatabaseConfig.forConfig[JdbcProfile]("", config)
  val excludeTableNames: Seq[String] = config.getStringList("excludeTableNames").asScala.toSeq
  import dbConfig._

  val tables = profile.defaultTables.map(_.filterNot { table =>
    excludeTableNames.contains(table.name.name)
  })

  // fetch data model
  val model = for {
    modelAction <- profile
      .createModel(Option(tables), ignoreInvalidDefaults = false) // you can filter specific tables here
  } yield modelAction

  val modelFuture = db.run(model)
  // customize code generator

  val codegenFuture = modelFuture
    .map { model =>
      new SourceCodeGenerator(model) {

        /**
          * デフォルトからの変更点
          * - slick.collection.heterogeneous.syntax のインポートを除去
          * - slick.model.ForeignKeyAction のインポートを除去
          */
        /** Generates code for the complete model (not wrapped in a package yet)
      @group Basic customization overrides */
        override def code = {
          // "import slick.model.ForeignKeyAction\n" +
          (if (tables.exists(table => table.hlistEnabled || table.isMappedToHugeClass)) {
             "import slick.collection.heterogeneous._\n"
             // "import slick.collection.heterogeneous.syntax._\n"
           } else "") +
          (if (tables.exists(_.PlainSqlMapper.enabled)) {
             "// NOTE: GetResult mappers for plain SQL are only generated for tables where Slick knows how to map the types of all columns.\n" +
             "import slick.jdbc.{GetResult => GR}\n"
           } else "") +
          codeForDDL +
          tables.map(_.code.mkString("\n")).mkString("\n\n")
        }

        /**
          * デフォルトからの変更点
          * - Tables object を生成しない
          * - Schema を未指定（None）に変更
          */
        override def packageCode(profile: String, pkg: String, container: String, parentType: Option[String]): String =
          s"""
           |package $pkg
           |// AUTO-GENERATED Slick data model
           |/** Slick data model trait for extension, choice of backend or usage in the cake pattern. (Make sure to initialize this late.) */
           |@SuppressWarnings(Array(
           |    "org.wartremover.warts.AsInstanceOf",
           |    "org.wartremover.warts.OptionPartial",
           |    "org.wartremover.warts.Throw",
           |    "org.wartremover.contrib.warts.MissingOverride",
           |    "org.wartremover.contrib.warts.SomeApply",
           |    "lerna.warts.NamingDef",
           |))
           |trait $container${parentType.map(t => s" extends $t").getOrElse("")} {
           |  val profile: slick.jdbc.JdbcProfile
           |  import profile.api._
           |  ${indent(code)}
           |}
       """.stripMargin.trim()

        // override table generator
        override def Table = new Table(_) {

          override def TableClass: AnyRef with TableClassDef = new TableClassDef {
            // Schema を None に変更
            override def code = {
              val prns = parents.map(" with " + _).mkString("")
              val args = model.name.schema.map(_ => s"""None""") ++ Seq("\"" + model.name.table + "\"")
              s"""
              |class $name(_tableTag: Tag) extends profile.api.Table[$elementType](_tableTag, ${args
                   .mkString(", ")})$prns {
              |  ${indent(body.map(_.mkString("\n")).mkString("\n\n"))}
              |}
              """.stripMargin
            }
          }
        }
      }
    }.map { codegen =>
      codegen.writeToFile(
        config.getString("profile").replaceFirst("""\$$""", ""),
        config.getString("outputDir"),
        config.getString("pkg"),
        container = "Tables",
        fileName = "Tables.scala",
      )
    }
  codegenFuture.onComplete {
    case Success(codegen) =>
      log.info("slick-codegen successful")
    case Failure(exception) =>
      log.error("slick-codegen failed", exception)
  }

  Await.result(codegenFuture, Duration.Inf)

}
