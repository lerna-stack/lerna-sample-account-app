package myapp.application.account

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import lerna.akka.entityreplication.typed.testkit.ReplicatedEntityBehaviorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }

class BankAccountBehaviorSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterEach with BeforeAndAfterAll {

  import BankAccountBehavior._

  private[this] val testKit = ActorTestKit()

  private[this] val bankAccountTestKit =
    ReplicatedEntityBehaviorTestKit[Command, DomainEvent, Account](
      testKit.system,
      BankAccountBehavior.TypeKey,
      entityId = "test-entity",
      behavior = context => BankAccountBehavior(context),
    )

  override def afterEach(): Unit = {
    bankAccountTestKit.clear()
    super.afterEach()
  }

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
    super.afterAll()
  }

  "A BankAccountBehavior" should {

    "increase a balance when it receives Deposit" in {
      val transactionId1 = 1L
      val result1        = bankAccountTestKit.runCommand(Deposit(transactionId1, amount = 1000, _))
      result1.eventOfType[Deposited].amount should be(1000)
      result1.state.balance should be(1000)
      result1.reply.balance should be(1000)

      val transactionId2 = 2L
      val result2        = bankAccountTestKit.runCommand(Deposit(transactionId2, amount = 2000, _))
      result2.eventOfType[Deposited].amount should be(2000)
      result2.state.balance should be(3000)
      result2.reply.balance should be(3000)
    }

    "decrease a balance when it receives Withdraw" in {
      val transactionId1 = 1L
      val result1        = bankAccountTestKit.runCommand(Deposit(transactionId1, amount = 3000, _))
      result1.reply.balance should be(3000)

      val transactionId2 = 2L
      val result2        = bankAccountTestKit.runCommand(Withdraw(transactionId2, amount = 1000, _))
      result2.eventOfType[Withdrew].amount should be(1000)
      result2.state.balance should be(2000)
      result2.replyOfType[WithdrawSucceeded].balance should be(2000)

      val transactionId3 = 3L
      val result3        = bankAccountTestKit.runCommand(Withdraw(transactionId3, amount = 2000, _))
      result3.eventOfType[Withdrew].amount should be(2000)
      result3.state.balance should be(0)
      result3.replyOfType[WithdrawSucceeded].balance should be(0)
    }

    "reject the request when it receives Withdraw if the balance is less than the request" in {
      val transactionId1 = 1L
      val result1        = bankAccountTestKit.runCommand(Deposit(transactionId1, amount = 3000, _))
      result1.reply.balance should be(3000)

      val transactionId2 = 2L
      val result2        = bankAccountTestKit.runCommand(Withdraw(transactionId2, amount = 5000, _))
      result2.replyOfType[ShortBalance]
    }

    "return a current balance when it receives GetBalance" in {
      val transactionId1 = 1L
      val result1        = bankAccountTestKit.runCommand(Deposit(transactionId1, amount = 3000, _))
      result1.reply.balance should be(3000)

      val result2 = bankAccountTestKit.runCommand(GetBalance)
      result2.reply.balance should be(3000)
    }

    "not increase a balance even if it receives multiple Deposit commands with same transactionId" in {

      def command[T](replyTo: ActorRef[DepositSucceeded]) =
        Deposit(transactionId = 1L, amount = 1000, replyTo)

      val result1 = bankAccountTestKit.runCommand(command _)
      result1.eventOfType[Deposited]
      result1.reply.balance should be(1000)

      val result2 = bankAccountTestKit.runCommand(command _)
      result2.hasNoEvents
      result2.reply.balance should be(1000)
    }

    "not decrease a balance even if it receives multiple Withdraw commands with same transactionId" in {

      val result1 = bankAccountTestKit.runCommand(Deposit(transactionId = 1L, amount = 1000, _))
      result1.reply.balance should be(1000)

      def command[T](replyTo: ActorRef[WithdrawReply]) =
        Withdraw(transactionId = 2L, amount = 1000, replyTo)

      val result2 = bankAccountTestKit.runCommand(command _)
      result2.eventOfType[Withdrew]
      result2.replyOfType[WithdrawSucceeded].balance should be(0)

      val result3 = bankAccountTestKit.runCommand(command _)
      result3.hasNoEvents
      result3.replyOfType[WithdrawSucceeded].balance should be(0)
    }

    "restore the balance after it restarts" in {
      val result1 = bankAccountTestKit.runCommand(Deposit(transactionId = 1L, amount = 1000, _))
      result1.reply.balance should be(1000)
      val result2 = bankAccountTestKit.runCommand(Withdraw(transactionId = 2L, amount = 500, _))
      result2.replyOfType[WithdrawSucceeded].balance should be(500)

      bankAccountTestKit.restart()
      bankAccountTestKit.state.balance should be(500)
    }
  }
}
