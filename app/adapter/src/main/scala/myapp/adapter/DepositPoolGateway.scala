package myapp.adapter

import scala.concurrent.Future

object DepositPoolGateway {
  final case class Deposit(cursor: Cursor, accountNo: String, amount: BigDecimal)
  final case class Body(data: Seq[DepositPoolGateway.Deposit])
}

trait DepositPoolGateway {
  import DepositPoolGateway._

  def fetch(cursor: Option[Cursor], limit: Int): Future[Body]
}
