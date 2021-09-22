package myapp.application.account

import akka.actor.typed.ActorRef
import lerna.akka.entityreplication.typed.testkit.ReplicatedEntityBehaviorTestKit
import lerna.testkit.akka.ScalaTestWithTypedActorTestKit
import lerna.util.trace.TraceId
import myapp.adapter.account.TransactionId
import myapp.utility.AppRequestContext
import myapp.utility.tenant.TenantA
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

class BankAccountBehaviorSpec extends ScalaTestWithTypedActorTestKit() with AnyWordSpecLike with BeforeAndAfterEach {

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

  "A BankAccountBehavior" should {

    "increase a balance when it receives Deposit" in {
      val transactionId1 = TransactionId("1")
      val result1        = bankAccountTestKit.runCommand[DepositReply](Deposit(transactionId1, amount = 1000, _))
      result1.eventOfType[Deposited].amount should be(1000)
      result1.state.balance should be(1000)
      result1.replyOfType[DepositSucceeded].balance should be(1000)

      val transactionId2 = TransactionId("2")
      val result2        = bankAccountTestKit.runCommand[DepositReply](Deposit(transactionId2, amount = 2000, _))
      result2.eventOfType[Deposited].amount should be(2000)
      result2.state.balance should be(3000)
      result2.replyOfType[DepositSucceeded].balance should be(3000)
    }

    "reject a Deposit request if the deposited balance will be exceeded the balance max limit" in {
      val balanceMaxLimit = BankAccountBehavior.BalanceMaxLimit

      val resultOfDepositingMaxAmount =
        bankAccountTestKit.runCommand[DepositReply](Deposit(TransactionId("1"), amount = balanceMaxLimit, _))
      resultOfDepositingMaxAmount.eventOfType[Deposited].amount should be(balanceMaxLimit)
      resultOfDepositingMaxAmount.state.balance should be(balanceMaxLimit)
      resultOfDepositingMaxAmount.replyOfType[DepositSucceeded].balance should be(balanceMaxLimit)

      val resultOfExcessBalance =
        bankAccountTestKit.runCommand[DepositReply](Deposit(TransactionId("2"), amount = 1, _))
      resultOfExcessBalance.eventOfType[BalanceExceeded]
      resultOfExcessBalance.state.balance should be(balanceMaxLimit)
      resultOfExcessBalance.replyOfType[ExcessBalance]
    }

    "decrease a balance when it receives Withdraw" in {
      val transactionId1 = TransactionId("1")
      val result1        = bankAccountTestKit.runCommand[DepositReply](Deposit(transactionId1, amount = 3000, _))
      result1.replyOfType[DepositSucceeded].balance should be(3000)

      val transactionId2 = TransactionId("2")
      val result2        = bankAccountTestKit.runCommand[WithdrawReply](Withdraw(transactionId2, amount = 1000, _))
      result2.eventOfType[Withdrew].amount should be(1000)
      result2.state.balance should be(2000)
      result2.replyOfType[WithdrawSucceeded].balance should be(2000)

      val transactionId3 = TransactionId("3")
      val result3        = bankAccountTestKit.runCommand[WithdrawReply](Withdraw(transactionId3, amount = 2000, _))
      result3.eventOfType[Withdrew].amount should be(2000)
      result3.state.balance should be(0)
      result3.replyOfType[WithdrawSucceeded].balance should be(0)
    }

    "reject the request when it receives Withdraw if the balance is less than the request" in {
      val transactionId1 = TransactionId("1")
      val result1        = bankAccountTestKit.runCommand[DepositReply](Deposit(transactionId1, amount = 3000, _))
      result1.replyOfType[DepositSucceeded].balance should be(3000)

      val transactionId2 = TransactionId("2")
      val result2        = bankAccountTestKit.runCommand[WithdrawReply](Withdraw(transactionId2, amount = 5000, _))
      result2.replyOfType[ShortBalance]
    }

    "return a current balance when it receives GetBalance" in {
      val transactionId1 = TransactionId("1")
      val result1        = bankAccountTestKit.runCommand[DepositReply](Deposit(transactionId1, amount = 3000, _))
      result1.replyOfType[DepositSucceeded].balance should be(3000)

      val result2 = bankAccountTestKit.runCommand[AccountBalance](GetBalance(_))
      result2.reply.balance should be(3000)
    }

    "not increase a balance even if it receives multiple Deposit commands with same transactionId" in {

      def command[T](replyTo: ActorRef[DepositReply]) =
        Deposit(TransactionId("1"), amount = 1000, replyTo)

      val result1 = bankAccountTestKit.runCommand[DepositReply](command)
      result1.eventOfType[Deposited]
      result1.replyOfType[DepositSucceeded].balance should be(1000)

      val result2 = bankAccountTestKit.runCommand[DepositReply](command)
      result2.hasNoEvents
      result2.replyOfType[DepositSucceeded].balance should be(1000)
    }

    "not decrease a balance even if it receives multiple Withdraw commands with same transactionId" in {

      val result1 = bankAccountTestKit.runCommand[DepositReply](Deposit(TransactionId("1"), amount = 1000, _))
      result1.replyOfType[DepositSucceeded].balance should be(1000)

      def command[T](replyTo: ActorRef[WithdrawReply]) =
        Withdraw(TransactionId("2"), amount = 1000, replyTo)

      val result2 = bankAccountTestKit.runCommand[WithdrawReply](command)
      result2.eventOfType[Withdrew]
      result2.replyOfType[WithdrawSucceeded].balance should be(0)

      val result3 = bankAccountTestKit.runCommand[WithdrawReply](command)
      result3.hasNoEvents
      result3.replyOfType[WithdrawSucceeded].balance should be(0)
    }

    "restore the balance after it restarts" in {
      val result1 = bankAccountTestKit.runCommand[DepositReply](Deposit(TransactionId("1"), amount = 1000, _))
      result1.replyOfType[DepositSucceeded].balance should be(1000)
      val result2 = bankAccountTestKit.runCommand[WithdrawReply](Withdraw(TransactionId("2"), amount = 500, _))
      result2.replyOfType[WithdrawSucceeded].balance should be(500)

      bankAccountTestKit.restart()
      bankAccountTestKit.state.balance should be(500)
    }
  }
}
