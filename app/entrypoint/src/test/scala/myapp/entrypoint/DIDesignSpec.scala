package myapp.entrypoint

import com.typesafe.config.Config
import lerna.testkit.airframe.DISessionSupport
import lerna.testkit.akka.ScalaTestWithTypedActorTestKit
import myapp.utility.scalatest.StandardSpec
import wvlet.airframe.Design

@SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
class DIDesignSpec extends ScalaTestWithTypedActorTestKit with StandardSpec with DISessionSupport {

  override protected val diDesign: Design = DIDesign.design(system).withProductionMode

  "DIDesign" should {

    "DIコンポーネントの登録忘れがない" in {
      // DIDesign に withProductionMode を付けているので、
      // build したときに全てのDIコンポーネントが即座に初期化される。
      // もし登録が漏れている場合は初期化のタイミングでエラーになる
      diSession.build[Config]
    }
  }
}
