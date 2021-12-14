package myapp.application.projection.transaction

object TransactionEventType extends Enumeration {
  type TransactionEventType = Value
  val Deposited = Value("Deposited")
  val Withdrew  = Value("Withdrew")
  val Refunded  = Value("Refunded")
}
