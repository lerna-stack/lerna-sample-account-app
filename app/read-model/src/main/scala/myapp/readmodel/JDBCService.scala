package myapp.readmodel

import com.typesafe.config.Config
import lerna.util.lang.Equals._
import myapp.utility.tenant.{ AppTenant, TenantA }
import slick.basic.DatabaseConfig
import slick.jdbc.{ JdbcBackend, JdbcProfile }
import wvlet.airframe._

class JDBCService(config: Config) {

  def db(implicit tenant: AppTenant): JdbcBackend#Database = {
    dbConfigMap(tenant).db
  }

  def dbConfig(implicit tenant: AppTenant): DatabaseConfig[JdbcProfile] = {
    dbConfigMap(tenant)
  }

  private[this] val dbConfigMap = AppTenant.values.map { tenant =>
    tenant -> generateDbConfig(tenant)
  }.toMap

  private[this] def generateDbConfig(tenant: AppTenant): DatabaseConfig[JdbcProfile] = {
    DatabaseConfig.forConfig(s"myapp.readmodel.rdbms.tenants.${tenant.id}", config)
  }

  /** trait Tables の override val profile: JdbcProfile 用
    *
    * ※ profile の指定は 末尾に `$` を付与して object 指定である必要あり
    * @return conf の profile から 取得した profile object (singleton)
    */
  private[readmodel] def profile: JdbcProfile = {
    dbConfigMap(TenantA).profile
      .ensuring(
        returnValue => dbConfigMap.values.forall(_.profile === returnValue),
        "全てのテナントで rdbms の profile は同じものを使う必要があります",
      )
  }

  private[this] def connectAndAddShutdownHookToAllDataBase(): Unit = {
    dbConfigMap.values.foreach { dbConfig =>
      // dbConfig.db は `lazy val` で定義されているためアクセスしないと DB に接続されない
      // 起動時にチェックして、リクエスト時に初めてエラーが判明することを回避する
      dbConfig.db.onShutdown { db =>
        db.close()
      }
    }
  }

  connectAndAddShutdownHookToAllDataBase()
}
