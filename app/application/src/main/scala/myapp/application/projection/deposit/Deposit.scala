package myapp.application.projection.deposit

import java.time.Instant

final case class Deposit(depositId: DepositId, accountNo: String, amount: BigInt, createdAt: Instant)
