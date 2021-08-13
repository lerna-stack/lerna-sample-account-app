package myapp.readmodel

import myapp.utility.scalatest.StandardSpec
import com.typesafe.config.{ Config, ConfigFactory }
import lerna.testkit.airframe.DISessionSupport
import wvlet.airframe.{ newDesign, Design }

class JDBCSupportSpec extends StandardSpec with DISessionSupport with JDBCSupport {

  @SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
  override protected val diDesign: Design = newDesign
    .add(ReadModeDIDesign.readModelDDesign)
    .bind[Config].toInstance(ConfigFactory.load("application-test.conf"))

  "JDBCSupport" should {

    import tableSeeds._
    import tables._
    import tables.profile.api._

    "テスト開始前にデータを準備し、結果を検証できる" in withJDBC { db =>
      db.prepare(
        AkkaProjectionOffsetStore += AkkaProjectionOffsetStoreRowSeed.copy(projectionName = "dummy"),
      )

      db.validate(AkkaProjectionOffsetStore.result.head) { record =>
        expect(record.projectionName === "dummy")
      }
    }
  }
}
