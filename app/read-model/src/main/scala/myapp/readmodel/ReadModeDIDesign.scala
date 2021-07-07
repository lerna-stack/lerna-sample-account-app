package myapp.readmodel

import myapp.readmodel.schema.Tables
import wvlet.airframe._

object ReadModeDIDesign extends ReadModeDIDesign

/** ReadModel プロジェクト内のコンポーネントの [[wvlet.airframe.Design]] を定義する
  */
// Airframe が生成するコードを Wartremover が誤検知してしまうため
@SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
trait ReadModeDIDesign {

  @SuppressWarnings(Array("lerna.warts.CyclomaticComplexity"))
  val readModelDDesign: Design = newDesign
    .bind[JDBCService].toSingleton
    .bind[Tables].toSingletonProvider { jdbcService: JDBCService =>
      new Tables {
        override val profile = jdbcService.profile
      }
    }
}
