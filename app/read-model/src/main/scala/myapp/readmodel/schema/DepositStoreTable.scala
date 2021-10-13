package myapp.readmodel.schema
// AUTO-GENERATED Slick data model for table DepositStore
trait DepositStoreTable {

  self:Tables  =>

  import profile.api._
  import slick.model.ForeignKeyAction
  // NOTE: GetResult mappers for plain SQL are only generated for tables where Slick knows how to map the types of all columns.
  import slick.jdbc.{GetResult => GR}
  /** Entity class storing rows of table DepositStore
   *  @param depositId Database column deposit_id SqlType(BIGINT), AutoInc, PrimaryKey
   *  @param accountNo Database column account_no SqlType(VARCHAR), Length(255,true)
   *  @param amount Database column amount SqlType(BIGINT)
   *  @param createdAt Database column created_at SqlType(TIMESTAMP) */
  case class DepositStoreRow(depositId: Long, accountNo: String, amount: Long, createdAt: java.sql.Timestamp)
  /** GetResult implicit for fetching DepositStoreRow objects using plain SQL queries */
  implicit def GetResultDepositStoreRow(implicit e0: GR[Long], e1: GR[String], e2: GR[java.sql.Timestamp]): GR[DepositStoreRow] = GR{
    prs => import prs._
    DepositStoreRow.tupled((<<[Long], <<[String], <<[Long], <<[java.sql.Timestamp]))
  }
  /** Table description of table deposit_store. Objects of this class serve as prototypes for rows in queries. */

  class DepositStore(_tableTag: Tag) extends profile.api.Table[DepositStoreRow](_tableTag, None, "deposit_store") {
    def * = (depositId, accountNo, amount, createdAt) <> (DepositStoreRow.tupled, DepositStoreRow.unapply)
    /** Maps whole row to an option. Useful for outer joins. */
    def ? = ((Rep.Some(depositId), Rep.Some(accountNo), Rep.Some(amount), Rep.Some(createdAt))).shaped.<>({r=>import r._; _1.map(_=> DepositStoreRow.tupled((_1.get, _2.get, _3.get, _4.get)))}, (_:Any) =>  throw new Exception("Inserting into ? projection not supported."))

    /** Database column deposit_id SqlType(BIGINT), AutoInc, PrimaryKey */
    val depositId: Rep[Long] = column[Long]("deposit_id", O.AutoInc, O.PrimaryKey)
    /** Database column account_no SqlType(VARCHAR), Length(255,true) */
    val accountNo: Rep[String] = column[String]("account_no", O.Length(255,varying=true))
    /** Database column amount SqlType(BIGINT) */
    val amount: Rep[Long] = column[Long]("amount")
    /** Database column created_at SqlType(TIMESTAMP) */
    val createdAt: Rep[java.sql.Timestamp] = column[java.sql.Timestamp]("created_at")
  }
                
  /** Collection-like TableQuery object for table DepositStore */
  lazy val DepositStore = new TableQuery(tag => new DepositStore(tag))
}
