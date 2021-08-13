package myapp.readmodel

import lerna.testkit.airframe.DISessionSupport
import myapp.readmodel.schema.{ TableSeeds, Tables }
import myapp.utility.tenant.{ AppTenant, TenantA }
import org.scalatest.{ BeforeAndAfterAll, TestSuite }
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.concurrent.ScalaFutures.{ timeout, whenReady }
import org.scalatest.time.{ Seconds, Span }
import org.scalatest.{ BeforeAndAfterAll, TestSuite }

trait JDBCSupport extends BeforeAndAfterAll {
  this: TestSuite with DISessionSupport =>

  implicit val tenant: AppTenant = TenantA // UT では全テナントで共通の設定を用いるので他のテナントでも問題ない

  @SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
  protected lazy val tableSeeds: TableSeeds = TableSeeds(diSession.build[Tables])
  import tableSeeds._

  @SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
  protected lazy val jdbcService: JDBCService = diSession.build[JDBCService]

  protected val defaultJDBCTimeout: PatienceConfiguration.Timeout = timeout(Span(30, Seconds))

  override def beforeAll(): Unit = {
    import tables.profile.api._
    whenReady(jdbcService.db.run(tables.schema.createIfNotExists), defaultJDBCTimeout) { _ =>
      afterDatabasePrepared()
    }
  }

  protected def afterDatabasePrepared(): Unit = {}

  override def afterAll(): Unit = {
    import tables.profile.api._
    whenReady(jdbcService.db.run(tables.schema.dropIfExists), defaultJDBCTimeout) { _ =>
      // do nothing
    }
  }

  class JDBCHelper {
    import tables.profile.api._
    def prepare(dbActions: DBIO[_]*): Unit = {
      val io = slick.dbio.DBIO.seq(dbActions: _*)
      whenReady(jdbcService.db.run(io), defaultJDBCTimeout) { _ => }
    }

    def validate[T](dbAction: DBIO[T])(validator: T => Unit): Unit = {
      whenReady(jdbcService.db.run(dbAction), defaultJDBCTimeout) { result =>
        validator(result)
      }
    }
  }

  def withJDBC(testCode: JDBCHelper => Any): Unit = {
    import tables.profile.api._
    whenReady(jdbcService.db.run(tables.schema.truncate), defaultJDBCTimeout) { _ =>
      testCode(new JDBCHelper)
      whenReady(jdbcService.db.run(tables.schema.truncate), defaultJDBCTimeout) { _ =>
        // do nothing
      }
    }
  }
}
