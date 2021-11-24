package myapp.application.account

import akka.actor.typed.ActorRef
import lerna.akka.entityreplication.typed.testkit.ReplicatedEntityBehaviorTestKit
import lerna.testkit.akka.ScalaTestWithTypedActorTestKit
import lerna.util.trace.TraceId
import myapp.adapter.account.{AccountNo, TransactionId}
import myapp.utility.AppRequestContext
import myapp.utility.tenant.TenantA
import org.scalatest.{BeforeAndAfterEach, Inside}
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class BankAccountBehaviorSpec
    extends ScalaTestWithTypedActorTestKit()
    with AnyWordSpecLike
    with BeforeAndAfterEach
    with Inside {

  import BankAccountBehavior._

  private[this] val tenant = TenantA

  private[this] val bankAccountTestKit =
    ReplicatedEntityBehaviorTestKit[Command, DomainEvent, Account](
      system,
      BankAccountBehavior.typeKey(tenant),
      entityId = "test-entity",
      behavior = context => BankAccountBehavior(context),
    )

  override def afterEach(): Unit = {
    bankAccountTestKit.clear()
    super.afterEach()
  }

  implicit private val appRequestContext: AppRequestContext = AppRequestContext(TraceId.unknown, tenant)

  private def generateRandomTraceId(): TraceId = {
    TraceId(UUID.randomUUID().toString)
  }

  "A BankAccountBehavior" should {

    "increase a balance when it receives Deposit" in {
      val accountNo = AccountNo("1")
      val transactionId1 = TransactionId("1")
      val result1        = bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, transactionId1, amount = 1000, replyTo))
      result1.eventOfType[Deposited].amount should be(1000)
      result1.state.balance should be(1000)
      result1.replyOfType[DepositSucceeded].balance should be(1000)

      val transactionId2 = TransactionId("2")
      val result2        = bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, transactionId2, amount = 2000, replyTo))
      result2.eventOfType[Deposited].amount should be(2000)
      result2.state.balance should be(3000)
      result2.replyOfType[DepositSucceeded].balance should be(3000)
    }

    "reject a Deposit request if the deposited balance will be exceeded the balance max limit" in {
      val accountNo = AccountNo("1")
      val balanceMaxLimit = BankAccountBehavior.BalanceMaxLimit

      val resultOfDepositingMaxAmount =
        bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, TransactionId("1"), amount = balanceMaxLimit, replyTo))
      resultOfDepositingMaxAmount.eventOfType[Deposited].amount should be(balanceMaxLimit)
      resultOfDepositingMaxAmount.state.balance should be(balanceMaxLimit)
      resultOfDepositingMaxAmount.replyOfType[DepositSucceeded].balance should be(balanceMaxLimit)

      val resultOfExcessBalance =
        bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, TransactionId("2"), amount = 1, replyTo))
      resultOfExcessBalance.eventOfType[BalanceExceeded]
      resultOfExcessBalance.state.balance should be(balanceMaxLimit)
      resultOfExcessBalance.replyOfType[ExcessBalance]
    }

    "reject the second Deposit request with the same transactionId if the first Deposit request fails due to an excess balance" in {
      val accountNo = AccountNo("1")
      val balanceMaxLimit = BankAccountBehavior.BalanceMaxLimit

      val depositingMaxAmountResult =
        bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, TransactionId("1"), amount = balanceMaxLimit, replyTo))
      depositingMaxAmountResult.replyOfType[DepositSucceeded].balance should be(balanceMaxLimit)

      def deposit[T](replyTo: ActorRef[DepositReply]) =
        Deposit(accountNo, TransactionId("2"), amount = 1, replyTo)

      val firstExcessBalanceResult =
        bankAccountTestKit.runCommand[DepositReply](deposit)
      firstExcessBalanceResult.replyOfType[ExcessBalance]

      val secondExcessBalanceResult =
        bankAccountTestKit.runCommand[DepositReply](deposit)
      secondExcessBalanceResult.hasNoEvents should be(true)
      secondExcessBalanceResult.replyOfType[ExcessBalance]
    }

    "not handle a Deposit request with a transactionId that is associated with another command" in {
      val accountNo = AccountNo("1")
      val initialDepositId = TransactionId("1")
      val initialDepositResult =
        bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, initialDepositId, amount = 1000, replyTo))
      initialDepositResult.replyOfType[DepositSucceeded].balance should be(1000)

      val initialWithdrawalId = TransactionId("2")
      val initialWithdrawalResult =
        bankAccountTestKit.runCommand[WithdrawReply](Withdraw(accountNo, initialWithdrawalId, amount = 300, _))
      initialWithdrawalResult.replyOfType[WithdrawSucceeded].balance should be(700)

      val depositResultWithInvalidId =
        bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, initialWithdrawalId, amount = 400, replyTo))
      depositResultWithInvalidId.hasNoEvents should be(true)
    }

    "decrease a balance when it receives Withdraw" in {
      val accountNo = AccountNo("1")
      val transactionId1 = TransactionId("1")
      val result1        = bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, transactionId1, amount = 3000, replyTo))
      result1.replyOfType[DepositSucceeded].balance should be(3000)

      val transactionId2 = TransactionId("2")
      val result2        = bankAccountTestKit.runCommand[WithdrawReply](Withdraw(accountNo, transactionId2, amount = 1000, _))
      result2.eventOfType[Withdrew].amount should be(1000)
      result2.state.balance should be(2000)
      result2.replyOfType[WithdrawSucceeded].balance should be(2000)

      val transactionId3 = TransactionId("3")
      val result3        = bankAccountTestKit.runCommand[WithdrawReply](Withdraw(accountNo, transactionId3, amount = 2000, _))
      result3.eventOfType[Withdrew].amount should be(2000)
      result3.state.balance should be(0)
      result3.replyOfType[WithdrawSucceeded].balance should be(0)
    }

    "reject the request when it receives Withdraw if the balance is less than the request" in {
      val accountNo = AccountNo("1")
      val transactionId1 = TransactionId("1")
      val result1        = bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, transactionId1, amount = 3000, replyTo))
      result1.replyOfType[DepositSucceeded].balance should be(3000)

      val transactionId2 = TransactionId("2")
      val result2        = bankAccountTestKit.runCommand[WithdrawReply](Withdraw(accountNo, transactionId2, amount = 5000, _))
      result2.replyOfType[ShortBalance]
    }

    "reject the second Withdraw request with the same transactionId if the first Withdraw request fails due to a short balance" in {
      val accountNo = AccountNo("1")
      val initialDepositResult =
        bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, TransactionId("1"), amount = 1000, replyTo))
      initialDepositResult.replyOfType[DepositSucceeded].balance should be(1000)

      def withdraw[T](replyTo: ActorRef[WithdrawReply]) =
        Withdraw(accountNo,TransactionId("2"), amount = 2000, replyTo)

      val firstWithdrawalResult =
        bankAccountTestKit.runCommand[WithdrawReply](withdraw)
      firstWithdrawalResult.replyOfType[ShortBalance]

      val secondWithdrawalResult =
        bankAccountTestKit.runCommand[WithdrawReply](withdraw)
      secondWithdrawalResult.hasNoEvents should be(true)
      secondWithdrawalResult.replyOfType[ShortBalance]
    }

    "not handle a Withdraw command with a transactionId that is associated with another command" in {
      val accountNo = AccountNo("1")
      val initialDepositId = TransactionId("1")
      val initialDepositResult =
        bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, initialDepositId, amount = 1000, replyTo))
      initialDepositResult.replyOfType[DepositSucceeded].balance should be(1000)

      val initialWithdrawalId = TransactionId("2")
      val initialWithdrawalResult =
        bankAccountTestKit.runCommand[WithdrawReply](Withdraw(accountNo, initialWithdrawalId, amount = 300, _))
      initialWithdrawalResult.replyOfType[WithdrawSucceeded].balance should be(700)

      val withdrawalResultWithInvalidId =
        bankAccountTestKit.runCommand[WithdrawReply](Withdraw(accountNo, initialDepositId, amount = 400, _))
      withdrawalResultWithInvalidId.hasNoEvents should be(true)
    }

    "return a current balance when it receives GetBalance" in {
      val accountNo = AccountNo("1")
      val transactionId1 = TransactionId("1")
      val result1        = bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, transactionId1, amount = 3000, replyTo))
      result1.replyOfType[DepositSucceeded].balance should be(3000)

      val result2 = bankAccountTestKit.runCommand[AccountBalance](GetBalance(_))
      result2.reply.balance should be(3000)
    }

    "not increase a balance even if it receives multiple Deposit commands with same transactionId" in {

      def command[T](replyTo: ActorRef[DepositReply]) =
        Deposit(AccountNo("1"), TransactionId("1"), amount = 1000, replyTo)

      val result1 = bankAccountTestKit.runCommand[DepositReply](command)
      result1.eventOfType[Deposited]
      result1.replyOfType[DepositSucceeded].balance should be(1000)

      val result2 = bankAccountTestKit.runCommand[DepositReply](command)
      result2.hasNoEvents
      result2.replyOfType[DepositSucceeded].balance should be(1000)
    }

    "not decrease a balance even if it receives multiple Withdraw commands with same transactionId" in {
      val accountNo = AccountNo("1")

      val result1 = bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, TransactionId("1"), amount = 1000, replyTo))
      result1.replyOfType[DepositSucceeded].balance should be(1000)

      def command[T](replyTo: ActorRef[WithdrawReply]) =
        Withdraw(accountNo, TransactionId("2"), amount = 1000, replyTo)

      val result2 = bankAccountTestKit.runCommand[WithdrawReply](command)
      result2.eventOfType[Withdrew]
      result2.replyOfType[WithdrawSucceeded].balance should be(0)

      val result3 = bankAccountTestKit.runCommand[WithdrawReply](command)
      result3.hasNoEvents
      result3.replyOfType[WithdrawSucceeded].balance should be(0)
    }

    "restore the balance after it restarts" in {
      val accountNo = AccountNo("1")
      val result1 = bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, TransactionId("1"), amount = 1000, replyTo))
      result1.replyOfType[DepositSucceeded].balance should be(1000)
      val result2 = bankAccountTestKit.runCommand[WithdrawReply](Withdraw(accountNo, TransactionId("2"), amount = 500, _))
      result2.replyOfType[WithdrawSucceeded].balance should be(500)

      bankAccountTestKit.restart()
      bankAccountTestKit.state.balance should be(500)
    }

    "refund the given amount when it receives a Refund command" in {
      val accountNo = AccountNo("1")

      val initialDepositResult =
        bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, TransactionId("1"), amount = 1000, replyTo))
      initialDepositResult.replyOfType[DepositSucceeded].balance should be(1000)

      val withdrawalId     = TransactionId("2")
      val withdrawalAmount = 300
      val withdrawalResult =
        bankAccountTestKit.runCommand[WithdrawReply](Withdraw(accountNo, withdrawalId, amount = withdrawalAmount, _))
      withdrawalResult.replyOfType[WithdrawSucceeded].balance should be(700)

      val refundId      = TransactionId("3")
      val refundAmount  = withdrawalAmount
      val refundContext = AppRequestContext(generateRandomTraceId(), tenant)
      val refundResult =
        bankAccountTestKit.runCommand[RefundReply](
          Refund(accountNo, refundId, withdrawalId, refundAmount, _)(refundContext),
        )
      val refunded = inside(refundResult.eventOfType[Refunded]) { event =>
        event.transactionId should be(refundId)
        event.withdrawalTransactionId should be(withdrawalId)
        event.appRequestContext should be(refundContext)
        event.amount should be(refundAmount)
        event
      }
      inside(refundResult.state) { account =>
        account.balance should be(1000)
        account.resentTransactions(refundId) should be(refunded)
      }
      refundResult.replyOfType[RefundSucceeded].balance should be(1000)

    }

    "refund the given amount even if the refunded balance is greater than BalanceMaxLimit" in {
      val accountNo = AccountNo("1")
      val balanceMaxLimit = BankAccountBehavior.BalanceMaxLimit

      val initialDepositResult =
        bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, TransactionId("1"), amount = balanceMaxLimit, replyTo))
      initialDepositResult.replyOfType[DepositSucceeded].balance should be(balanceMaxLimit)

      val withdrawalId = TransactionId("2")
      val withdrawalResult =
        bankAccountTestKit.runCommand[WithdrawReply](Withdraw(accountNo, withdrawalId, amount = balanceMaxLimit, _))
      withdrawalResult.replyOfType[WithdrawSucceeded].balance should be(0)

      val secondDepositResult =
        bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, TransactionId("3"), 1, replyTo))
      secondDepositResult.replyOfType[DepositSucceeded].balance should be(1)

      val refundId      = TransactionId("4")
      val refundContext = AppRequestContext(generateRandomTraceId(), tenant)
      val refundResult =
        bankAccountTestKit.runCommand[RefundReply](
          Refund(accountNo, refundId, withdrawalId, balanceMaxLimit, _)(refundContext),
        )
      val refunded = inside(refundResult.eventOfType[Refunded]) { event =>
        event.transactionId should be(refundId)
        event.withdrawalTransactionId should be(withdrawalId)
        event.appRequestContext should be(refundContext)
        event.amount should be(balanceMaxLimit)
        event
      }
      val expectedBalance = balanceMaxLimit + 1
      inside(refundResult.state) { account =>
        account.balance should be(expectedBalance)
        account.resentTransactions(refundId) should be(refunded)
      }
      refundResult.replyOfType[RefundSucceeded].balance should be(expectedBalance)

    }

    "reject a Refund command with a negative amount" in {
      val accountNo = AccountNo("1")

      val initialDepositResult =
        bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, TransactionId("1"), amount = 1000, replyTo))
      initialDepositResult.replyOfType[DepositSucceeded].balance should be(1000)

      val withdrawalId = TransactionId("2")
      val withdrawalResult =
        bankAccountTestKit.runCommand[WithdrawReply](Withdraw(accountNo, withdrawalId, amount = 300, _))
      withdrawalResult.replyOfType[WithdrawSucceeded].balance should be(700)

      val refundId             = TransactionId("3")
      val negativeRefundAmount = -1
      val refundContext        = AppRequestContext(generateRandomTraceId(), tenant)
      val refundResult =
        bankAccountTestKit.runCommand[RefundReply](
          Refund(accountNo, refundId, withdrawalId, negativeRefundAmount, _)(refundContext),
        )
      val invalidRefundRequested = inside(refundResult.eventOfType[InvalidRefundRequested]) { event =>
        event.transactionId should be(refundId)
        event.withdrawalTransactionId should be(withdrawalId)
        event.appRequestContext should be(refundContext)
        event.amount should be(negativeRefundAmount)
        event
      }
      inside(refundResult.state) { account =>
        account.balance should be(700)
        account.resentTransactions(refundId) should be(invalidRefundRequested)
      }
      refundResult.replyOfType[InvalidRefundCommand]

    }

    "not refund the given amount if a second Refund command has the same transactionId as the first command" in {
      val accountNo = AccountNo("1")

      val initialDepositResult =
        bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, TransactionId("1"), amount = 1000, replyTo))
      initialDepositResult.replyOfType[DepositSucceeded].balance should be(1000)

      val withdrawalId     = TransactionId("2")
      val withdrawalAmount = 300
      val withdrawalResult =
        bankAccountTestKit.runCommand[WithdrawReply](Withdraw(accountNo, withdrawalId, amount = withdrawalAmount, _))
      withdrawalResult.replyOfType[WithdrawSucceeded].balance should be(700)

      val refundId     = TransactionId("3")
      val refundAmount = withdrawalAmount
      val firstRefundResult =
        bankAccountTestKit.runCommand[RefundReply](
          Refund(accountNo, refundId, withdrawalId, refundAmount, _),
        )
      firstRefundResult.replyOfType[RefundSucceeded].balance should be(1000)

      val secondRefundContext = AppRequestContext(generateRandomTraceId(), tenant)
      val secondRefundResult =
        bankAccountTestKit.runCommand[RefundReply](
          Refund(accountNo, refundId, withdrawalId, refundAmount, _)(secondRefundContext),
        )
      secondRefundResult.hasNoEvents should be(true)
      secondRefundResult.replyOfType[RefundSucceeded].balance should be(1000)

    }

    "reject a second Refund command if the second command has the same transactionId but a different withdrawalTransactionId" in {
      val accountNo = AccountNo("1")

      val initialDepositResult =
        bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, TransactionId("1"), amount = 1000, replyTo))
      initialDepositResult.replyOfType[DepositSucceeded].balance should be(1000)

      val withdrawalId     = TransactionId("2")
      val withdrawalAmount = 300
      val withdrawalResult =
        bankAccountTestKit.runCommand[WithdrawReply](Withdraw(accountNo, withdrawalId, amount = withdrawalAmount, _))
      withdrawalResult.replyOfType[WithdrawSucceeded].balance should be(700)

      val anotherWithdrawalId = TransactionId("3")
      val anotherWithdrawalResult =
        bankAccountTestKit.runCommand[WithdrawReply](Withdraw(accountNo, anotherWithdrawalId, amount = 500, _))
      anotherWithdrawalResult.replyOfType[WithdrawSucceeded].balance should be(200)

      val refundId           = TransactionId("4")
      val refundAmount       = withdrawalAmount
      val firstRefundContext = AppRequestContext(generateRandomTraceId(), tenant)
      val firstRefundResult =
        bankAccountTestKit.runCommand[RefundReply](
          Refund(accountNo, refundId, withdrawalId, refundAmount, _)(firstRefundContext),
        )
      firstRefundResult.replyOfType[RefundSucceeded].balance should be(500)

      val secondRefundContext = AppRequestContext(generateRandomTraceId(), tenant)
      val secondRefundResult = {
        bankAccountTestKit.runCommand[RefundReply](
          Refund(accountNo, refundId, anotherWithdrawalId, refundAmount, _)(secondRefundContext),
        )
      }
      secondRefundResult.hasNoEvents should be(true)
      secondRefundResult.replyOfType[InvalidRefundCommand]

    }

    "reject a second Refund command if the second command has the same transactionId but a different amount" in {
      val accountNo = AccountNo("1")

      val initialDepositResult =
        bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, TransactionId("1"), amount = 1000, replyTo))
      initialDepositResult.replyOfType[DepositSucceeded].balance should be(1000)

      val withdrawalId     = TransactionId("2")
      val withdrawalAmount = 300
      val withdrawalResult =
        bankAccountTestKit.runCommand[WithdrawReply](Withdraw(accountNo, withdrawalId, amount = withdrawalAmount, _))
      withdrawalResult.replyOfType[WithdrawSucceeded].balance should be(700)

      val refundId           = TransactionId("3")
      val firstRefundAmount  = withdrawalAmount
      val firstRefundContext = AppRequestContext(generateRandomTraceId(), tenant)
      val firstRefundResult =
        bankAccountTestKit.runCommand[RefundReply](
          Refund(accountNo, refundId, withdrawalId, firstRefundAmount, _)(firstRefundContext),
        )
      firstRefundResult.replyOfType[RefundSucceeded].balance should be(1000)

      val secondRefundAmount = firstRefundAmount + 1
      assert(secondRefundAmount !== firstRefundAmount, "The second refund amount should not equal to the first one.")
      val secondRefundContext = AppRequestContext(generateRandomTraceId(), tenant)
      val secondRefundResult = {
        bankAccountTestKit.runCommand[RefundReply](
          Refund(accountNo, refundId, withdrawalId, secondRefundAmount, _)(secondRefundContext),
        )
      }
      secondRefundResult.hasNoEvents should be(true)
      secondRefundResult.replyOfType[InvalidRefundCommand]

    }

    "reject a Refund command with a transactionId that is already associated with another command" in {
      val accountNo = AccountNo("1")
      val initialDepositId = TransactionId("1")
      val initialDepositResult =
        bankAccountTestKit.runCommand[DepositReply]((replyTo: ActorRef[DepositReply]) => Deposit(accountNo, initialDepositId, amount = 1000, replyTo))
      initialDepositResult.replyOfType[DepositSucceeded].balance should be(1000)

      val withdrawalId     = TransactionId("2")
      val withdrawalAmount = 300
      val withdrawalResult =
        bankAccountTestKit.runCommand[WithdrawReply](Withdraw(accountNo, withdrawalId, amount = withdrawalAmount, _))
      withdrawalResult.replyOfType[WithdrawSucceeded].balance should be(700)

      val refundId      = initialDepositId // Assign already used TransactionId
      val refundAmount  = withdrawalAmount
      val refundContext = AppRequestContext(generateRandomTraceId(), tenant)
      val refundResult =
        bankAccountTestKit.runCommand[RefundReply](
          Refund(accountNo, refundId, withdrawalId, refundAmount, _)(refundContext),
        )
      refundResult.hasNoEvents should be(true)
      refundResult.replyOfType[InvalidRefundCommand]

    }

  }
}
