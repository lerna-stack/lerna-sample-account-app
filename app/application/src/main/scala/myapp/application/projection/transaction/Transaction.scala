package myapp.application.projection.transaction

final case class Transaction(transactionId: String, eventType: TransactionEventType.Value, amount: BigInt)
