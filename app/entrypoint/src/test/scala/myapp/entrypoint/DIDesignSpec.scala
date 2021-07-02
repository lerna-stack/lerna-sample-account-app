package myapp.entrypoint

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.Config
import lerna.testkit.airframe.DISessionSupport
import myapp.utility.scalatest.StandardSpec
import wvlet.airframe.Design

@SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
class DIDesignSpec extends StandardSpec with DISessionSupport {

  private val system = ActorSystem(Behaviors.empty, "MyAppSystem")

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
