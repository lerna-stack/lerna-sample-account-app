package myapp.application.projection.transaction

import myapp.adapter.Comment
import myapp.adapter.account.{ AccountNo, TransactionId }
import myapp.application.projection.transaction.TransactionEventType.TransactionEventType

final case class Transaction(
    transactionId: TransactionId,
    eventType: TransactionEventType,
    accountNo: AccountNo,
    amount: BigInt,
    balance: BigInt,
    transactedAt: Long,
    comment: Option[Comment] = None,
)
