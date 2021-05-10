package myapp.entrypoint

import akka.actor.ActorSystem
import com.typesafe.config.Config
import myapp.presentation.PresentationDIDesign
import wvlet.airframe._

@SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
object DIDesign {
  @SuppressWarnings(Array("lerna.warts.CyclomaticComplexity"))
  def design(system: ActorSystem): Design =
    newDesign
      .bind[ActorSystem].toInstance(system)
      .bind[MyApp].toSingleton
      .bind[Config].toSingletonProvider[ActorSystem] { system => system.settings.config }
      .add(PresentationDIDesign.presentationDesign)
}
