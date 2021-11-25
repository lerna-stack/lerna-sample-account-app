package myapp.adapter.account

final case class TransactionDto(
    transactionId: String,
    transactionType: String,
    amount: Int,
    balance: Int,
    transactedAt: Long,
)
