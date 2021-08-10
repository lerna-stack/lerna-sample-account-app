package myapp.readmodel.schema
// AUTO-GENERATED Slick data model
/** Slick data model trait for extension, choice of backend or usage in the cake pattern. (Make sure to initialize this late.) */
@SuppressWarnings(Array(
    "org.wartremover.warts.AsInstanceOf",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Throw",
    "org.wartremover.contrib.warts.MissingOverride",
    "org.wartremover.contrib.warts.SomeApply",
    "lerna.warts.NamingDef",
))
trait Tables {
  val profile: slick.jdbc.JdbcProfile
  import profile.api._
  // NOTE: GetResult mappers for plain SQL are only generated for tables where Slick knows how to map the types of all columns.
  import slick.jdbc.{GetResult => GR}

  /** DDL for all tables. Call .create to execute. */
  lazy val schema: profile.SchemaDescription = AkkaProjectionOffsetStore.schema
  @deprecated("Use .schema instead of .ddl", "3.0")
  def ddl = schema

  /** Entity class storing rows of table AkkaProjectionOffsetStore
   *  @param projectionName Database column projection_name SqlType(VARCHAR), Length(255,true)
   *  @param projectionKey Database column projection_key SqlType(VARCHAR), Length(255,true)
   *  @param currentOffset Database column current_offset SqlType(VARCHAR), Length(255,true)
   *  @param manifest Database column manifest SqlType(VARCHAR), Length(4,true)
   *  @param mergeable Database column mergeable SqlType(BIT)
   *  @param lastUpdated Database column last_updated SqlType(BIGINT) */
  case class AkkaProjectionOffsetStoreRow(projectionName: String, projectionKey: String, currentOffset: String, manifest: String, mergeable: Boolean, lastUpdated: Long)
  /** GetResult implicit for fetching AkkaProjectionOffsetStoreRow objects using plain SQL queries */
  implicit def GetResultAkkaProjectionOffsetStoreRow(implicit e0: GR[String], e1: GR[Boolean], e2: GR[Long]): GR[AkkaProjectionOffsetStoreRow] = GR{
    prs => import prs._
    AkkaProjectionOffsetStoreRow.tupled((<<[String], <<[String], <<[String], <<[String], <<[Boolean], <<[Long]))
  }
  /** Table description of table akka_projection_offset_store. Objects of this class serve as prototypes for rows in queries. */

  class AkkaProjectionOffsetStore(_tableTag: Tag) extends profile.api.Table[AkkaProjectionOffsetStoreRow](_tableTag, None, "akka_projection_offset_store") {
    def * = (projectionName, projectionKey, currentOffset, manifest, mergeable, lastUpdated) <> (AkkaProjectionOffsetStoreRow.tupled, AkkaProjectionOffsetStoreRow.unapply)
    /** Maps whole row to an option. Useful for outer joins. */
    def ? = ((Rep.Some(projectionName), Rep.Some(projectionKey), Rep.Some(currentOffset), Rep.Some(manifest), Rep.Some(mergeable), Rep.Some(lastUpdated))).shaped.<>({r=>import r._; _1.map(_=> AkkaProjectionOffsetStoreRow.tupled((_1.get, _2.get, _3.get, _4.get, _5.get, _6.get)))}, (_:Any) =>  throw new Exception("Inserting into ? projection not supported."))

    /** Database column projection_name SqlType(VARCHAR), Length(255,true) */
    val projectionName: Rep[String] = column[String]("projection_name", O.Length(255,varying=true))
    /** Database column projection_key SqlType(VARCHAR), Length(255,true) */
    val projectionKey: Rep[String] = column[String]("projection_key", O.Length(255,varying=true))
    /** Database column current_offset SqlType(VARCHAR), Length(255,true) */
    val currentOffset: Rep[String] = column[String]("current_offset", O.Length(255,varying=true))
    /** Database column manifest SqlType(VARCHAR), Length(4,true) */
    val manifest: Rep[String] = column[String]("manifest", O.Length(4,varying=true))
    /** Database column mergeable SqlType(BIT) */
    val mergeable: Rep[Boolean] = column[Boolean]("mergeable")
    /** Database column last_updated SqlType(BIGINT) */
    val lastUpdated: Rep[Long] = column[Long]("last_updated")

    /** Primary key of AkkaProjectionOffsetStore (database name akka_projection_offset_store_PK) */
    val pk = primaryKey("akka_projection_offset_store_PK", (projectionName, projectionKey))

    /** Index over (projectionName) (database name projection_name_index) */
    val index1 = index("projection_name_index", projectionName)
  }
                
  /** Collection-like TableQuery object for table AkkaProjectionOffsetStore */
  lazy val AkkaProjectionOffsetStore = new TableQuery(tag => new AkkaProjectionOffsetStore(tag))
}
