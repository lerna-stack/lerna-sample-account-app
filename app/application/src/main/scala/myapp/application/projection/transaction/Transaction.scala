package myapp.application.projection.transaction

import myapp.adapter.account.TransactionId

final case class Transaction(transactionId: TransactionId, eventType: TransactionEventType.Value, amount: BigInt)
