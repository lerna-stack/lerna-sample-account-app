package myapp.readmodel.schema
// AUTO-GENERATED Slick data model
/** Slick data model trait for extension, choice of backend or usage in the cake pattern. (Make sure to initialize this late.) */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.AsInstanceOf",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Throw",
    "org.wartremover.contrib.warts.MissingOverride",
    "org.wartremover.contrib.warts.SomeApply",
    "lerna.warts.NamingDef",
  ),
)
trait Tables {
  val profile: slick.jdbc.JdbcProfile
  import profile.api._

  /** DDL for all tables. Call .create to execute. */
  lazy val schema: profile.SchemaDescription = profile.DDL(Nil, Nil)
  @deprecated("Use .schema instead of .ddl", "3.0")
  def ddl = schema
}
