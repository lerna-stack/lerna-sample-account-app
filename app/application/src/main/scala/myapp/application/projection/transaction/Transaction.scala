package myapp.application.projection.transaction

import myapp.adapter.account.TransactionId
import myapp.application.projection.transaction.TransactionEventType.TransactionEventType

final case class Transaction(transactionId: TransactionId, eventType: TransactionEventType, amount: BigInt)
