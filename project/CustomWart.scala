import wartremover.WartRemover.autoImport.Wart

object CustomWart {
  val Awaits               = Wart.custom("lerna.warts.Awaits")
  val CyclomaticComplexity = Wart.custom("lerna.warts.CyclomaticComplexity")
  val NamingClass          = Wart.custom("lerna.warts.NamingClass")
  val NamingDef            = Wart.custom("lerna.warts.NamingDef")
  val NamingObject         = Wart.custom("lerna.warts.NamingObject")
  val NamingPackage        = Wart.custom("lerna.warts.NamingPackage")
  val NamingVal            = Wart.custom("lerna.warts.NamingVal")
  val NamingVar            = Wart.custom("lerna.warts.NamingVar")
}
