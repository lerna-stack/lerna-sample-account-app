package myapp.application.projection.transaction

object TransactionEventType extends Enumeration {
  val Deposited = Value("Deposited")
  val Withdrew  = Value("Withdrew")
  val Refunded  = Value("Refunded")
}
