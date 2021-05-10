import sbt.taskKey

object Helper {

  /** より上位のシステムプロパティを優先します
    */
  def distinctJavaOptions(javaOptions: Seq[String]*): Seq[String] = {
    javaOptions.flatten
      .groupBy(_.split('=').head)
      .mapValues(_.head)
      .values.toSeq
  }

  def sbtJavaOptions: Seq[String] = {
    // 前方一致で除外
    lazy val excludeSbtJavaOptions = Set(
      "os.",
      "user.",
      "java.",
      "sun.",
      "awt.",
      "jline.",
      "jna.",
      "jnidispatch.",
      "sbt.",
    )
    sys.props
      .filterNot { case (key, _) => excludeSbtJavaOptions.exists(key.startsWith) }.map {
        case (k, v) => s"-D$k=$v"
      }.toSeq
  }

  def isDebugging: Boolean = {
    import java.lang.management.ManagementFactory
    import scala.collection.JavaConverters._
    ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.exists(_.startsWith("-agentlib:jdwp="))
  }

}
