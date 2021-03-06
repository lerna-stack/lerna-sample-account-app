package myapp.adapter.query

final case class TransactionDto(
    transactionId: String,
    transactionType: String,
    amount: Long,
    balance: Long,
    transactedAt: Long,
    comment: String,
)
