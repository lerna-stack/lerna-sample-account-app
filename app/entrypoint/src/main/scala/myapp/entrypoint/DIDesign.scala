package myapp.entrypoint

import akka.actor.typed.ActorSystem
import com.typesafe.config.Config
import myapp.presentation.PresentationDIDesign
import wvlet.airframe._

@SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
object DIDesign {
  @SuppressWarnings(Array("lerna.warts.CyclomaticComplexity"))
  def design(system: ActorSystem[Nothing]): Design =
    newDesign
      .bind[ActorSystem[Nothing]].toInstance(system)
      .bind[MyApp].toSingleton
      .bind[Config].toSingletonProvider[ActorSystem[Nothing]] { system => system.settings.config }
      .add(PresentationDIDesign.presentationDesign)
}
