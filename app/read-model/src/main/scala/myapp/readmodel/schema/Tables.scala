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
  // NOTE: GetResult mappers for plain SQL are only generated for tables where Slick knows how to map the types of all columns.
  import slick.jdbc.{ GetResult => GR }

  /** DDL for all tables. Call .create to execute. */
  lazy val schema: profile.SchemaDescription =
    AkkaProjectionOffsetStore.schema ++ DepositStore.schema ++ TransactionStore.schema
  @deprecated("Use .schema instead of .ddl", "3.0")
  def ddl = schema

  /** Entity class storing rows of table AkkaProjectionOffsetStore
    *  @param projectionName Database column projection_name SqlType(VARCHAR), Length(255,true)
    *  @param projectionKey Database column projection_key SqlType(VARCHAR), Length(255,true)
    *  @param currentOffset Database column current_offset SqlType(VARCHAR), Length(255,true)
    *  @param manifest Database column manifest SqlType(VARCHAR), Length(4,true)
    *  @param mergeable Database column mergeable SqlType(BIT)
    *  @param lastUpdated Database column last_updated SqlType(BIGINT)
    */
  case class AkkaProjectionOffsetStoreRow(
      projectionName: String,
      projectionKey: String,
      currentOffset: String,
      manifest: String,
      mergeable: Boolean,
      lastUpdated: Long,
  )

  /** GetResult implicit for fetching AkkaProjectionOffsetStoreRow objects using plain SQL queries */
  implicit def GetResultAkkaProjectionOffsetStoreRow(implicit
      e0: GR[String],
      e1: GR[Boolean],
      e2: GR[Long],
  ): GR[AkkaProjectionOffsetStoreRow] = GR { prs =>
    import prs._
    AkkaProjectionOffsetStoreRow.tupled((<<[String], <<[String], <<[String], <<[String], <<[Boolean], <<[Long]))
  }

  /** Table description of table akka_projection_offset_store. Objects of this class serve as prototypes for rows in queries. */

  class AkkaProjectionOffsetStore(_tableTag: Tag)
      extends profile.api.Table[AkkaProjectionOffsetStoreRow](_tableTag, None, "akka_projection_offset_store") {
    def * = (
      projectionName,
      projectionKey,
      currentOffset,
      manifest,
      mergeable,
      lastUpdated,
    ) <> (AkkaProjectionOffsetStoreRow.tupled, AkkaProjectionOffsetStoreRow.unapply)

    /** Maps whole row to an option. Useful for outer joins. */
    def ? = (
      (
        Rep.Some(projectionName),
        Rep.Some(projectionKey),
        Rep.Some(currentOffset),
        Rep.Some(manifest),
        Rep.Some(mergeable),
        Rep.Some(lastUpdated),
      ),
    ).shaped.<>(
      { r =>
        import r._; _1.map(_ => AkkaProjectionOffsetStoreRow.tupled((_1.get, _2.get, _3.get, _4.get, _5.get, _6.get)))
      },
      (_: Any) => throw new Exception("Inserting into ? projection not supported."),
    )

    /** Database column projection_name SqlType(VARCHAR), Length(255,true) */
    val projectionName: Rep[String] = column[String]("projection_name", O.Length(255, varying = true))

    /** Database column projection_key SqlType(VARCHAR), Length(255,true) */
    val projectionKey: Rep[String] = column[String]("projection_key", O.Length(255, varying = true))

    /** Database column current_offset SqlType(VARCHAR), Length(255,true) */
    val currentOffset: Rep[String] = column[String]("current_offset", O.Length(255, varying = true))

    /** Database column manifest SqlType(VARCHAR), Length(4,true) */
    val manifest: Rep[String] = column[String]("manifest", O.Length(4, varying = true))

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

  /** Entity class storing rows of table DepositStore
    *  @param depositId Database column deposit_id SqlType(BIGINT), AutoInc, PrimaryKey
    *  @param accountNo Database column account_no SqlType(VARCHAR), Length(255,true)
    *  @param amount Database column amount SqlType(BIGINT)
    *  @param createdAt Database column created_at SqlType(TIMESTAMP)
    */
  case class DepositStoreRow(depositId: Long, accountNo: String, amount: Long, createdAt: java.sql.Timestamp)

  /** GetResult implicit for fetching DepositStoreRow objects using plain SQL queries */
  implicit
  def GetResultDepositStoreRow(implicit e0: GR[Long], e1: GR[String], e2: GR[java.sql.Timestamp]): GR[DepositStoreRow] =
    GR { prs =>
      import prs._
      DepositStoreRow.tupled((<<[Long], <<[String], <<[Long], <<[java.sql.Timestamp]))
    }

  /** Table description of table deposit_store. Objects of this class serve as prototypes for rows in queries. */

  class DepositStore(_tableTag: Tag) extends profile.api.Table[DepositStoreRow](_tableTag, None, "deposit_store") {
    def * = (depositId, accountNo, amount, createdAt) <> (DepositStoreRow.tupled, DepositStoreRow.unapply)

    /** Maps whole row to an option. Useful for outer joins. */
    def ? = ((Rep.Some(depositId), Rep.Some(accountNo), Rep.Some(amount), Rep.Some(createdAt))).shaped.<>(
      { r => import r._; _1.map(_ => DepositStoreRow.tupled((_1.get, _2.get, _3.get, _4.get))) },
      (_: Any) => throw new Exception("Inserting into ? projection not supported."),
    )

    /** Database column deposit_id SqlType(BIGINT), AutoInc, PrimaryKey */
    val depositId: Rep[Long] = column[Long]("deposit_id", O.AutoInc, O.PrimaryKey)

    /** Database column account_no SqlType(VARCHAR), Length(255,true) */
    val accountNo: Rep[String] = column[String]("account_no", O.Length(255, varying = true))

    /** Database column amount SqlType(BIGINT) */
    val amount: Rep[Long] = column[Long]("amount")

    /** Database column created_at SqlType(TIMESTAMP) */
    val createdAt: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("created_at")
  }

  /** Collection-like TableQuery object for table DepositStore */
  lazy val DepositStore = new TableQuery(tag => new DepositStore(tag))

  /** Entity class storing rows of table TransactionStore
    *  @param transactionId Database column transaction_id SqlType(VARCHAR), PrimaryKey, Length(255,true)
    *  @param transactionType Database column transaction_type SqlType(CHAR), Length(16,false)
    *  @param accountNo Database column account_no SqlType(VARCHAR), Length(255,true)
    *  @param amount Database column amount SqlType(BIGINT)
    *  @param transactedAt Database column transacted_at SqlType(BIGINT)
    */
  case class TransactionStoreRow(
      transactionId: String,
      transactionType: String,
      accountNo: String,
      amount: Long,
      transactedAt: Long,
  )

  /** GetResult implicit for fetching TransactionStoreRow objects using plain SQL queries */
  implicit
  def GetResultTransactionStoreRow(implicit e0: GR[String], e1: GR[Long]): GR[TransactionStoreRow] = GR { prs =>
    import prs._
    TransactionStoreRow.tupled((<<[String], <<[String], <<[String], <<[Long], <<[Long]))
  }

  /** Table description of table transaction_store. Objects of this class serve as prototypes for rows in queries. */

  class TransactionStore(_tableTag: Tag)
      extends profile.api.Table[TransactionStoreRow](_tableTag, None, "transaction_store") {
    def * = (
      transactionId,
      transactionType,
      accountNo,
      amount,
      transactedAt,
    ) <> (TransactionStoreRow.tupled, TransactionStoreRow.unapply)

    /** Maps whole row to an option. Useful for outer joins. */
    def ? = (
      (
        Rep.Some(transactionId),
        Rep.Some(transactionType),
        Rep.Some(accountNo),
        Rep.Some(amount),
        Rep.Some(transactedAt),
      ),
    ).shaped.<>(
      { r => import r._; _1.map(_ => TransactionStoreRow.tupled((_1.get, _2.get, _3.get, _4.get, _5.get))) },
      (_: Any) => throw new Exception("Inserting into ? projection not supported."),
    )

    /** Database column transaction_id SqlType(VARCHAR), PrimaryKey, Length(255,true) */
    val transactionId: Rep[String] = column[String]("transaction_id", O.PrimaryKey, O.Length(255, varying = true))

    /** Database column transaction_type SqlType(CHAR), Length(16,false) */
    val transactionType: Rep[String] = column[String]("transaction_type", O.Length(16, varying = false))

    /** Database column account_no SqlType(VARCHAR), Length(255,true) */
    val accountNo: Rep[String] = column[String]("account_no", O.Length(255, varying = true))

    /** Database column amount SqlType(BIGINT) */
    val amount: Rep[Long] = column[Long]("amount")

    /** Database column transacted_at SqlType(BIGINT) */
    val transactedAt: Rep[Long] = column[Long]("transacted_at")
  }

  /** Collection-like TableQuery object for table TransactionStore */
  lazy val TransactionStore = new TableQuery(tag => new TransactionStore(tag))
}
