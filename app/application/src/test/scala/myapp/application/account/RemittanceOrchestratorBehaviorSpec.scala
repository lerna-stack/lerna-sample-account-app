package myapp.application.account

import akka.actor.testkit.typed.scaladsl.{ LoggingTestKit, SerializationTestKit, TestProbe }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.testkit._
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import lerna.testkit.akka.ScalaTestWithTypedActorTestKit
import lerna.util.trace.TraceId
import myapp.adapter.account.BankAccountApplication.{ DepositResult, RefundResult, WithdrawalResult }
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionId }
import myapp.utility.AppRequestContext
import myapp.utility.scalatest.StandardSpec
import myapp.utility.tenant.TenantA
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object RemittanceOrchestratorBehaviorSpec {
  import RemittanceOrchestratorBehavior._

  private val config = {
    val appConfig = ConfigFactory.load("application-test")
    EventSourcedBehaviorTestKit.config
      .withFallback(PersistenceTestKitSnapshotPlugin.config)
      .withFallback(appConfig)
  }

  private val serializationSettings = {
    SerializationSettings.enabled
      // Prefer to verify the equality of the result of the serialization.
      .withVerifyEquality(true)
      // Some commands do not have to be serializable.
      // Since we cannot choose which commands should be verified by this flag,
      // we switch this flag off and verify commands' serialization manually.
      .withVerifyCommands(false)
      // FIXME Some states are not serializable.
      .withVerifyState(false)
  }

  private val settings: Settings = {
    Settings(
      journalPluginId = PersistenceTestKitPlugin.PluginId,
      snapshotPluginId = PersistenceTestKitSnapshotPlugin.PluginId,
      withdrawalRetryDelay = 100.millis,
      depositRetryDelay = 100.millis,
      refundRetryDelay = 100.millis,
      passivateTimeout = 300.millis,
      persistenceFailureRestartMinBackOff = 100.millis,
      persistenceFailureRestartMaxBackOff = 500.millis,
      persistenceFailureRestartRandomFactor = 0.4,
    )
  }

  private val tenant = TenantA

  private def generateAppRequestContext(): AppRequestContext = {
    val traceId = TraceId(UUID.randomUUID().toString)
    AppRequestContext(traceId, tenant)
  }

  private final class SequentialTransactionIdFactory extends Context.TransactionIdFactory {
    private val counter = new AtomicInteger(1)
    def setCounter(value: Int): Unit = {
      counter.set(value)
    }
    override def generate(): TransactionId = {
      val value = counter.getAndIncrement()
      TransactionId(value.toString)
    }
  }

  private final class SuccessEventually[+R](failureLimit: Int, failureValue: R, successValue: R) {
    private val attempts = new AtomicInteger(0)
    def apply(): R = {
      val attempt = attempts.getAndIncrement()
      if (attempt < failureLimit) {
        failureValue
      } else {
        successValue
      }
    }
  }

}

@SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf", "org.wartremover.warts.Product"))
final class RemittanceOrchestratorBehaviorSpec
    extends ScalaTestWithTypedActorTestKit(RemittanceOrchestratorBehaviorSpec.config)
    with StandardSpec
    with Inside
    with MockFactory {

  import RemittanceOrchestratorBehavior._
  import RemittanceOrchestratorBehaviorSpec._

  private def createBehavior(
      initialState: State,
      bankAccountApplication: BankAccountApplication,
      shard: ActorRef[ClusterSharding.ShardCommand],
      self: Option[ActorRef[Command]],
      transactionIdFactory: Context.TransactionIdFactory,
  ): (Behavior[Command], PersistenceId) = {
    val entityId      = EntityId(UUID.randomUUID().toString)
    val persistenceId = PersistenceId(typeKey(tenant).name, entityId.value)
    val behavior = RemittanceOrchestratorBehavior(
      entityId = entityId,
      persistenceId = persistenceId,
      settings,
      bankAccountApplication,
      shard,
      self,
      initialState,
      transactionIdFactory,
    )
    (behavior, persistenceId)
  }

  private def createEventSourcedBehaviorTestKit(
      initialState: State,
      bankAccountApplication: BankAccountApplication,
      shard: ActorRef[ClusterSharding.ShardCommand] = createTestProbe[ClusterSharding.ShardCommand]().ref,
      self: Option[ActorRef[Command]] = None,
      transactionIdFactory: Context.TransactionIdFactory = Context.DefaultTransactionIdFactory,
  ): (EventSourcedBehaviorTestKit[Command, DomainEvent, State], PersistenceId) = {
    val (behavior, persistenceId) =
      createBehavior(initialState, bankAccountApplication, shard, self, transactionIdFactory)
    val testKit = EventSourcedBehaviorTestKit[Command, DomainEvent, State](system, behavior, serializationSettings)
    (testKit, persistenceId)
  }

  private def consumeCommandEmittedOnRecoveryCompleted(probe: TestProbe[Command], initialState: State): Unit = {
    initialState match {
      case _: State.Empty =>
      // Do nothing
      case _: State.WithdrawingFromSource =>
        probe.expectMessage(WithdrawFromSource)
      case _: State.DepositingToDestination =>
        probe.expectMessage(DepositToDestination)
      case _: State.RefundingToSource =>
        probe.expectMessage(RefundToSource)
      case _: State.TransactionCompletedState =>
      // Do nothing
    }
  }

  private def createEventSourcedBehaviorTestKitWithSelfProbe(
      initialState: State,
      bankAccountApplication: BankAccountApplication,
      shard: ActorRef[ClusterSharding.ShardCommand] = createTestProbe[ClusterSharding.ShardCommand]().ref,
      transactionIdFactory: Context.TransactionIdFactory = Context.DefaultTransactionIdFactory,
  ): (EventSourcedBehaviorTestKit[Command, DomainEvent, State], PersistenceId, TestProbe[Command]) = {
    val probe = createTestProbe[Command]()
    val (testKit, persistenceId) =
      createEventSourcedBehaviorTestKit(
        initialState,
        bankAccountApplication,
        shard,
        Option(probe.ref),
        transactionIdFactory,
      )
    consumeCommandEmittedOnRecoveryCompleted(probe, initialState)
    (testKit, persistenceId, probe)
  }

  private def spawnWithSelfProbe(
      initialState: State,
      bankAccountApplication: BankAccountApplication,
      shard: ActorRef[ClusterSharding.ShardCommand] = createTestProbe[ClusterSharding.ShardCommand]().ref,
      transactionIdFactory: Context.TransactionIdFactory = Context.DefaultTransactionIdFactory,
  ): (ActorRef[Command], PersistenceId, TestProbe[Command]) = {
    val probe = createTestProbe[Command]()
    val (behavior, persistenceId) =
      createBehavior(initialState, bankAccountApplication, shard, self = Option(probe.ref), transactionIdFactory)
    val orchestrator = spawn(behavior)
    consumeCommandEmittedOnRecoveryCompleted(probe, initialState)
    (orchestrator, persistenceId, probe)
  }

  "RemittanceOrchestratorBehavior" when {

    def verifyThatOrchestratorStopsWhenItReceivesStopCommand(state: State): Unit = {
      val bankAccountApplication = mock[BankAccountApplication]

      val (orchestrator, _, _) =
        spawnWithSelfProbe(state, bankAccountApplication)

      val probe = createTestProbe[Command]()
      orchestrator ! Stop
      probe.expectTerminated(orchestrator)
    }

    def verifyThatOrchestratorLogsUnexpectedReceivedCommandAsWarn(state: State, command: Command): Unit = {
      val bankAccountApplication = mock[BankAccountApplication]

      val (orchestrator, _, _) =
        createEventSourcedBehaviorTestKitWithSelfProbe(state, bankAccountApplication)

      LoggingTestKit
        .warn(s"Unexpected command ${command.toString}")
        .expect {
          orchestrator.runCommand(command)
        }
    }

    def verifyThatOrchestratorSendPassivateToShardAfterPassivateTimeout(state: State): Unit = {
      val bankAccountApplication = mock[BankAccountApplication]

      val shardProbe = createTestProbe[ClusterSharding.ShardCommand]()
      val (orchestrator, _, _) =
        spawnWithSelfProbe(state, bankAccountApplication, shard = shardProbe.ref)

      // Wait enough longer than the passivateTimeout.
      val timeout = settings.passivateTimeout * 2
      shardProbe.expectMessage(timeout, ClusterSharding.Passivate(orchestrator))
    }

    def verifyThatOrchestratorDontSendPassivateToShardAfterPassivateTimeout(state: State): Unit = {
      val bankAccountApplication = mock[BankAccountApplication]

      val shardProbe = createTestProbe[ClusterSharding.ShardCommand]()
      val (_, _, _) =
        createEventSourcedBehaviorTestKitWithSelfProbe(state, bankAccountApplication, shard = shardProbe.ref)

      // Wait enough longer than the passivateTimeout.
      val timeout = settings.passivateTimeout * 2
      shardProbe.expectNoMessage(timeout)
    }

    "State.Empty" should {

      val emptyState = State.Empty(tenant)

      "create a transaction when it receives a Remit command" in {

        val bankAccountApplication = mock[BankAccountApplication]

        val transactionIdFactory = new SequentialTransactionIdFactory()
        transactionIdFactory.setCounter(1)
        val expectedWithdrawalTransactionId = TransactionId("1")
        val expectedDepositTransactionId    = TransactionId("2")
        val expectedRefundTransactionId     = TransactionId("3")

        val (orchestrator, _, selfProbe) = createEventSourcedBehaviorTestKitWithSelfProbe(
          emptyState,
          bankAccountApplication,
          transactionIdFactory = transactionIdFactory,
        )

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("source")
        val destinationAccountNo                          = AccountNo("destination")
        val remittanceAmount                              = BigInt(100)
        val replyProbe                                    = testKit.createTestProbe[RemitReply]()
        val remitCommand                                  = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)
        val result                                        = orchestrator.runCommand(remitCommand)

        replyProbe.expectNoMessage()
        selfProbe.expectMessage(remitCommand)
        selfProbe.expectMessage(WithdrawFromSource)
        inside(result.eventOfType[TransactionCreated]) { event =>
          expect(event.appRequestContext === appRequestContext)
          expect(event.sourceAccountNo === sourceAccountNo)
          expect(event.destinationAccountNo === destinationAccountNo)
          expect(event.amount === remittanceAmount)
          expect(event.withdrawalTransactionId === expectedWithdrawalTransactionId)
          expect(event.depositTransactionId === expectedDepositTransactionId)
          expect(event.refundTransactionId === expectedRefundTransactionId)
        }
        inside(result.stateOfType[State.WithdrawingFromSource]) { state =>
          expect(state.appRequestContext === appRequestContext)
          expect(state.sourceAccountNo === sourceAccountNo)
          expect(state.destinationAccountNo === destinationAccountNo)
          expect(state.remittanceAmount === remittanceAmount)
          expect(state.withdrawalTransactionId === expectedWithdrawalTransactionId)
          expect(state.depositTransactionId === expectedDepositTransactionId)
          expect(state.refundTransactionId === expectedRefundTransactionId)
        }

      }

      "reject a Remit command that has the same source and destination account, persist a InvalidRemittanceRequested event, and then move to a EarlyFailed state" in {

        val bankAccountApplication = mock[BankAccountApplication]

        val (orchestrator, _, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(emptyState, bankAccountApplication)

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("account")
        val destinationAccountNo                          = sourceAccountNo
        val remittanceAmount                              = BigInt(100)
        val replyProbe                                    = testKit.createTestProbe[RemitReply]()
        val remitCommand                                  = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)
        val result                                        = orchestrator.runCommand(remitCommand)

        replyProbe.expectNoMessage()
        selfProbe.expectMessage(remitCommand)
        selfProbe.expectMessage(CompleteTransaction)
        inside(result.eventOfType[InvalidRemittanceRequested]) { event =>
          expect(event.appRequestContext === appRequestContext)
          expect(event.sourceAccountNo === sourceAccountNo)
          expect(event.destinationAccountNo === destinationAccountNo)
          expect(event.amount === remittanceAmount)
        }
        inside(result.stateOfType[State.EarlyFailed]) { state =>
          expect(state.appRequestContext === appRequestContext)
          expect(state.sourceAccountNo === sourceAccountNo)
          expect(state.destinationAccountNo === destinationAccountNo)
          expect(state.remittanceAmount === remittanceAmount)
        }

      }

      "reject a Remit command with a negative remittance amount, persist a InvalidRemittanceRequested event, and then move to a EarlyFailed state" in {

        val bankAccountApplication = mock[BankAccountApplication]

        val (orchestrator, _, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(emptyState, bankAccountApplication)

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("source")
        val destinationAccountNo                          = AccountNo("destination")
        val remittanceAmount                              = BigInt(-1)
        val replyProbe                                    = testKit.createTestProbe[RemitReply]()
        val remitCommand                                  = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)
        val result                                        = orchestrator.runCommand(remitCommand)

        replyProbe.expectNoMessage()
        selfProbe.expectMessage(remitCommand)
        selfProbe.expectMessage(CompleteTransaction)
        inside(result.eventOfType[InvalidRemittanceRequested]) { event =>
          expect(event.appRequestContext === appRequestContext)
          expect(event.sourceAccountNo === sourceAccountNo)
          expect(event.destinationAccountNo === destinationAccountNo)
          expect(event.amount === remittanceAmount)
        }
        inside(result.stateOfType[State.EarlyFailed]) { state =>
          expect(state.appRequestContext === appRequestContext)
          expect(state.sourceAccountNo === sourceAccountNo)
          expect(state.destinationAccountNo === destinationAccountNo)
          expect(state.remittanceAmount === remittanceAmount)
        }

      }

      "stop when it receives a Stop command" in {
        verifyThatOrchestratorStopsWhenItReceivesStopCommand(emptyState)
      }

      "log unexpected commands as warn" in {

        val verifyLogCommandAsWarn = verifyThatOrchestratorLogsUnexpectedReceivedCommandAsWarn(emptyState, _)
        verifyLogCommandAsWarn(Passivate)
        verifyLogCommandAsWarn(WithdrawFromSource)
        verifyLogCommandAsWarn(WithdrawCompleted(WithdrawalResult.Succeeded(0)))
        verifyLogCommandAsWarn(WithdrawFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(DepositToDestination)
        verifyLogCommandAsWarn(DepositCompleted(DepositResult.Succeeded(123)))
        verifyLogCommandAsWarn(DepositFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(RefundToSource)
        verifyLogCommandAsWarn(RefundCompleted(RefundResult.Succeeded(456)))
        verifyLogCommandAsWarn(RefundFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(RefundFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(CompleteTransaction)

      }

      "not send a Passivate command when it receives no commands in the passivateTimeout" in {
        verifyThatOrchestratorDontSendPassivateToShardAfterPassivateTimeout(emptyState)
      }

    }

    "State.Withdrawing" should {

      implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
      val sourceAccountNo                               = AccountNo("source")
      val destinationAccountNo                          = AccountNo("destination")
      val remittanceAmount                              = BigInt(100)
      val withdrawalTransactionId                       = Context.DefaultTransactionIdFactory.generate()
      val depositTransactionId                          = Context.DefaultTransactionIdFactory.generate()
      val refundTransactionId                           = Context.DefaultTransactionIdFactory.generate()
      val withdrawingFromSource = State.WithdrawingFromSource(
        sourceAccountNo,
        destinationAccountNo,
        remittanceAmount,
        withdrawalTransactionId,
        depositTransactionId,
        refundTransactionId,
      )

      "succeed to withdraw the remittance amount from the source account, persist a WithdrawalSucceeded event, and then move to a DepositingDestination state" in {

        val bankAccountApplication       = mock[BankAccountApplication]
        val sourceBalanceAfterWithdrawal = 100 // It does not matter this value.
        (bankAccountApplication
          .withdraw(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, withdrawalTransactionId, remittanceAmount, appRequestContext)
          .returns(Future.successful(WithdrawalResult.Succeeded(sourceBalanceAfterWithdrawal)))

        val (orchestrator, persistenceId, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(withdrawingFromSource, bankAccountApplication)

        orchestrator.runCommand(WithdrawFromSource)

        selfProbe.expectMessage(DepositToDestination)
        inside(orchestrator.persistenceTestKit.expectNextPersistedType[WithdrawalSucceeded](persistenceId.id)) {
          event =>
            expect(event.appRequestContext === appRequestContext)
            expect(event.accountNo === sourceAccountNo)
            expect(event.transactionId === withdrawalTransactionId)
            expect(event.amount === remittanceAmount)
        }
        inside(orchestrator.getState()) {
          case state: State.DepositingToDestination =>
            expect(state.appRequestContext === appRequestContext)
            expect(state.sourceAccountNo === sourceAccountNo)
            expect(state.destinationAccountNo === destinationAccountNo)
            expect(state.remittanceAmount === remittanceAmount)
            expect(state.withdrawalTransactionId === withdrawalTransactionId)
            expect(state.depositTransactionId === depositTransactionId)
            expect(state.refundTransactionId === refundTransactionId)
        }

      }

      "fail to withdraw the remittance amount from the source account due to ShortBalance, persist a BalanceShorted event, and then move to a Failed state" in {

        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .withdraw(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, withdrawalTransactionId, remittanceAmount, appRequestContext)
          .returns(Future.successful(WithdrawalResult.ShortBalance))

        val (orchestrator, persistenceId, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(withdrawingFromSource, bankAccountApplication)

        orchestrator.runCommand(WithdrawFromSource)

        selfProbe.expectMessage(CompleteTransaction)
        inside(orchestrator.persistenceTestKit.expectNextPersistedType[BalanceShorted](persistenceId.id)) { event =>
          expect(event.appRequestContext === appRequestContext)
          expect(event.accountNo === sourceAccountNo)
          expect(event.transactionId === withdrawalTransactionId)
          expect(event.amount === remittanceAmount)
        }
        inside(orchestrator.getState()) {
          case state: State.Failed =>
            expect(state.appRequestContext === appRequestContext)
            expect(state.sourceAccountNo === sourceAccountNo)
            expect(state.destinationAccountNo === destinationAccountNo)
            expect(state.remittanceAmount === remittanceAmount)
            expect(state.withdrawalTransactionId === withdrawalTransactionId)
            expect(state.depositTransactionId === depositTransactionId)
            expect(state.refundTransactionId === refundTransactionId)
            expect(state.failureReply === ShortBalance)
        }

      }

      "retry withdrawal several times due to a timeout, succeed the withdrawal, persist a WithdrawalSucceeded event, and then move to a DepositingToDestination state" in {

        val failureLimit = 3
        val newWithdrawalResult = new SuccessEventually(
          failureLimit,
          Future.successful(WithdrawalResult.Timeout),
          Future.successful(WithdrawalResult.Succeeded(0)),
        )
        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .withdraw(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, withdrawalTransactionId, remittanceAmount, appRequestContext)
          .repeat(failureLimit + 1)
          .onCall { _ => newWithdrawalResult() }

        val (orchestrator, persistenceId, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(withdrawingFromSource, bankAccountApplication)

        // NOTE: Warn logs are crucial; This test should verify warn logs are actually written.
        LoggingTestKit
          .warn("Withdrawal failed due to a timeout.")
          .withMessageContains("WithdrawingFromSource")
          .withOccurrences(failureLimit)
          .expect {

            orchestrator.runCommand(WithdrawFromSource)

            selfProbe.expectMessage(DepositToDestination)
            inside(orchestrator.persistenceTestKit.expectNextPersistedType[WithdrawalSucceeded](persistenceId.id)) {
              event =>
                expect(event.appRequestContext === appRequestContext)
                expect(event.accountNo === sourceAccountNo)
                expect(event.transactionId === withdrawalTransactionId)
                expect(event.amount === remittanceAmount)
            }
            inside(orchestrator.getState()) {
              case state: State.DepositingToDestination =>
                expect(state.appRequestContext === appRequestContext)
                expect(state.sourceAccountNo === sourceAccountNo)
                expect(state.destinationAccountNo === destinationAccountNo)
                expect(state.remittanceAmount === remittanceAmount)
                expect(state.withdrawalTransactionId === withdrawalTransactionId)
                expect(state.depositTransactionId === depositTransactionId)
                expect(state.refundTransactionId === refundTransactionId)
            }

          }

      }

      "retry withdrawal several times due to an unexpected exception, succeed the withdrawal, persist a WithdrawalSucceeded event, and then move to a DepositingToDestination state" in {
        // NOTE: We might not be able to recover this failure automatically.
        // This test supposes that this failure will recover eventually by human operation.

        val failureLimit = 3
        val newWithdrawalResult = new SuccessEventually(
          failureLimit,
          Future.failed(new IllegalStateException("unexpected")),
          Future.successful(WithdrawalResult.Succeeded(0)),
        )
        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .withdraw(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, withdrawalTransactionId, remittanceAmount, appRequestContext)
          .repeat(failureLimit + 1)
          .onCall(_ => newWithdrawalResult())

        val (orchestrator, persistenceId, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(withdrawingFromSource, bankAccountApplication)

        // NOTE: Error logs are crucial; This test should verify error logs are actually written.
        LoggingTestKit
          .error("Withdrawal failed with an unexpected exception.")
          .withMessageContains("WithdrawingFromSource")
          .withOccurrences(failureLimit)
          .expect {

            orchestrator.runCommand(WithdrawFromSource)

            selfProbe.expectMessage(DepositToDestination)
            inside(orchestrator.persistenceTestKit.expectNextPersistedType[WithdrawalSucceeded](persistenceId.id)) {
              event =>
                expect(event.appRequestContext === appRequestContext)
                expect(event.accountNo === sourceAccountNo)
                expect(event.transactionId === withdrawalTransactionId)
                expect(event.amount === remittanceAmount)
            }
            inside(orchestrator.getState()) {
              case state: State.DepositingToDestination =>
                expect(state.appRequestContext === appRequestContext)
                expect(state.sourceAccountNo === sourceAccountNo)
                expect(state.destinationAccountNo === destinationAccountNo)
                expect(state.remittanceAmount === remittanceAmount)
                expect(state.withdrawalTransactionId === withdrawalTransactionId)
                expect(state.depositTransactionId === depositTransactionId)
                expect(state.refundTransactionId === refundTransactionId)
            }

          }

      }

      "stop when it receives a Stop command" in {
        verifyThatOrchestratorStopsWhenItReceivesStopCommand(withdrawingFromSource)
      }

      "log unexpected commands as warn" in {

        val verifyLogCommandAsWarn = verifyThatOrchestratorLogsUnexpectedReceivedCommandAsWarn(withdrawingFromSource, _)
        verifyLogCommandAsWarn(Passivate)
        verifyLogCommandAsWarn(DepositToDestination)
        verifyLogCommandAsWarn(DepositCompleted(DepositResult.Succeeded(123)))
        verifyLogCommandAsWarn(DepositFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(RefundToSource)
        verifyLogCommandAsWarn(RefundCompleted(RefundResult.Succeeded(456)))
        verifyLogCommandAsWarn(RefundFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(RefundFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(CompleteTransaction)

      }

      "not send a Passivate command when it receives no commands in the passivateTimeout" in {
        verifyThatOrchestratorDontSendPassivateToShardAfterPassivateTimeout(withdrawingFromSource)
      }

    }

    "State.Depositing" should {

      implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
      val sourceAccountNo                               = AccountNo("source")
      val destinationAccountNo                          = AccountNo("destination")
      val remittanceAmount                              = BigInt(100)
      val withdrawalTransactionId                       = Context.DefaultTransactionIdFactory.generate()
      val depositTransactionId                          = Context.DefaultTransactionIdFactory.generate()
      val refundTransactionId                           = Context.DefaultTransactionIdFactory.generate()
      val depositingToDestination = State.DepositingToDestination(
        sourceAccountNo,
        destinationAccountNo,
        remittanceAmount,
        withdrawalTransactionId,
        depositTransactionId,
        refundTransactionId,
      )

      "succeed to deposit the remittance amount to the destination account, persist a DepositSucceeded event, and then move to a Succeeded state" in {

        val bankAccountApplication = mock[BankAccountApplication]
        // It does not matter this value if this value is greater than or equal to the remittance amount (the deposit amount);
        // It would be great that this value is not equal to the deposit amount.
        val destinationBalanceAfterDeposit = remittanceAmount + 50
        (bankAccountApplication
          .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(destinationAccountNo, depositTransactionId, remittanceAmount, appRequestContext)
          .returns(Future.successful(DepositResult.Succeeded(destinationBalanceAfterDeposit)))

        val (orchestrator, persistenceId, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(depositingToDestination, bankAccountApplication)

        orchestrator.runCommand(DepositToDestination)

        selfProbe.expectMessage(CompleteTransaction)
        inside(orchestrator.persistenceTestKit.expectNextPersistedType[DepositSucceeded](persistenceId.id)) { event =>
          expect(event.appRequestContext === appRequestContext)
          expect(event.accountNo === destinationAccountNo)
          expect(event.transactionId === depositTransactionId)
          expect(event.amount === remittanceAmount)
        }
        inside(orchestrator.getState()) {
          case state: State.Succeeded =>
            expect(state.appRequestContext === appRequestContext)
            expect(state.sourceAccountNo === sourceAccountNo)
            expect(state.destinationAccountNo === destinationAccountNo)
            expect(state.remittanceAmount === remittanceAmount)
            expect(state.withdrawalTransactionId === withdrawalTransactionId)
            expect(state.depositTransactionId === depositTransactionId)
            expect(state.refundTransactionId === refundTransactionId)
        }

      }

      "fail to deposit the remittance amount to the destination account due to ExcessBalance, persist a ExcessBalance event, and then move to a RefundingToSource state" in {

        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(destinationAccountNo, depositTransactionId, remittanceAmount, appRequestContext)
          .returns(Future.successful(DepositResult.ExcessBalance))

        val (orchestrator, persistenceId, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(depositingToDestination, bankAccountApplication)

        orchestrator.runCommand(DepositToDestination)

        selfProbe.expectMessage(RefundToSource)
        inside(orchestrator.persistenceTestKit.expectNextPersistedType[BalanceExceeded](persistenceId.id)) { event =>
          expect(event.appRequestContext === appRequestContext)
          expect(event.accountNo === destinationAccountNo)
          expect(event.transactionId === depositTransactionId)
          expect(event.amount === remittanceAmount)
        }
        inside(orchestrator.getState()) {
          case state: State.RefundingToSource =>
            expect(state.appRequestContext === appRequestContext)
            expect(state.sourceAccountNo === sourceAccountNo)
            expect(state.destinationAccountNo === destinationAccountNo)
            expect(state.remittanceAmount === remittanceAmount)
            expect(state.withdrawalTransactionId === withdrawalTransactionId)
            expect(state.depositTransactionId === depositTransactionId)
            expect(state.refundTransactionId === refundTransactionId)
            expect(state.refundReason === State.RefundingToSource.RefundReason.BalanceExceeded)
        }

      }

      "retry deposit several times due to a timeout, succeed the deposit, persist a DepositSucceeded event, and then move to a Succeeded state" in {

        val failureLimit = 3
        val newDepositResult = new SuccessEventually(
          failureLimit,
          Future.successful(DepositResult.Timeout),
          Future.successful(DepositResult.Succeeded(0)),
        )
        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(destinationAccountNo, depositTransactionId, remittanceAmount, appRequestContext)
          .repeat(failureLimit + 1)
          .onCall(_ => newDepositResult())

        val (orchestrator, persistenceId, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(depositingToDestination, bankAccountApplication)

        // NOTE: Warn logs are crucial; This test should verify warn logs are actually written.
        LoggingTestKit
          .warn("Deposit failed due to a timeout.")
          .withMessageContains("DepositingToDestination")
          .withOccurrences(failureLimit)
          .expect {

            orchestrator.runCommand(DepositToDestination)

            selfProbe.expectMessage(CompleteTransaction)
            inside(orchestrator.persistenceTestKit.expectNextPersistedType[DepositSucceeded](persistenceId.id)) {
              event =>
                expect(event.appRequestContext === appRequestContext)
                expect(event.accountNo === destinationAccountNo)
                expect(event.transactionId === depositTransactionId)
                expect(event.amount === remittanceAmount)
            }
            inside(orchestrator.getState()) {
              case state: State.Succeeded =>
                expect(state.appRequestContext === appRequestContext)
                expect(state.sourceAccountNo === sourceAccountNo)
                expect(state.destinationAccountNo === destinationAccountNo)
                expect(state.remittanceAmount === remittanceAmount)
                expect(state.withdrawalTransactionId === withdrawalTransactionId)
                expect(state.depositTransactionId === depositTransactionId)
                expect(state.refundTransactionId === refundTransactionId)
            }

          }

      }

      "retry deposit several times due to an unexpected exception, succeed the deposit, persist a DepositSucceeded event, and then move to a Succeeded state" in {
        // NOTE: We might not be able to recover this failure automatically.
        // This test supposes that this failure will recover eventually by human operation.

        val failureLimit = 3
        val newDepositResult = new SuccessEventually(
          failureLimit,
          Future.failed(new IllegalStateException("unexpected")),
          Future.successful(DepositResult.Succeeded(0)),
        )
        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(destinationAccountNo, depositTransactionId, remittanceAmount, appRequestContext)
          .repeat(failureLimit + 1)
          .onCall(_ => newDepositResult())

        val (orchestrator, persistenceId, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(depositingToDestination, bankAccountApplication)

        // NOTE: Error logs are crucial; This test should verify error logs are actually written.
        LoggingTestKit
          .error("Deposit failed with an unexpected exception.")
          .withMessageContains("DepositingToDestination")
          .withOccurrences(failureLimit)
          .expect {

            orchestrator.runCommand(DepositToDestination)

            selfProbe.expectMessage(CompleteTransaction)
            inside(orchestrator.persistenceTestKit.expectNextPersistedType[DepositSucceeded](persistenceId.id)) {
              event =>
                expect(event.appRequestContext === appRequestContext)
                expect(event.accountNo === destinationAccountNo)
                expect(event.transactionId === depositTransactionId)
                expect(event.amount === remittanceAmount)
            }
            inside(orchestrator.getState()) {
              case state: State.Succeeded =>
                expect(state.appRequestContext === appRequestContext)
                expect(state.sourceAccountNo === sourceAccountNo)
                expect(state.destinationAccountNo === destinationAccountNo)
                expect(state.remittanceAmount === remittanceAmount)
                expect(state.withdrawalTransactionId === withdrawalTransactionId)
                expect(state.depositTransactionId === depositTransactionId)
                expect(state.refundTransactionId === refundTransactionId)
            }

          }

      }

      "stop when it receives a Stop command" in {
        verifyThatOrchestratorStopsWhenItReceivesStopCommand(depositingToDestination)
      }

      "log unexpected commands as warn" in {

        val verifyLogCommandAsWarn =
          verifyThatOrchestratorLogsUnexpectedReceivedCommandAsWarn(depositingToDestination, _)
        verifyLogCommandAsWarn(Passivate)
        verifyLogCommandAsWarn(WithdrawFromSource)
        verifyLogCommandAsWarn(WithdrawCompleted(WithdrawalResult.Succeeded(0)))
        verifyLogCommandAsWarn(WithdrawFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(RefundToSource)
        verifyLogCommandAsWarn(RefundCompleted(RefundResult.Succeeded(456)))
        verifyLogCommandAsWarn(RefundFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(RefundFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(CompleteTransaction)

      }

      "not send a Passivate command when it receives no commands in the passivateTimeout" in {
        verifyThatOrchestratorDontSendPassivateToShardAfterPassivateTimeout(depositingToDestination)
      }

    }

    "State.Refunding" should {

      implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
      val sourceAccountNo                               = AccountNo("source")
      val destinationAccountNo                          = AccountNo("destination")
      val remittanceAmount                              = BigInt(100)
      val withdrawalTransactionId                       = Context.DefaultTransactionIdFactory.generate()
      val depositTransactionId                          = Context.DefaultTransactionIdFactory.generate()
      val refundTransactionId                           = Context.DefaultTransactionIdFactory.generate()
      val refundingToSource = State.RefundingToSource(
        sourceAccountNo,
        destinationAccountNo,
        remittanceAmount,
        withdrawalTransactionId,
        depositTransactionId,
        refundTransactionId,
        State.RefundingToSource.RefundReason.BalanceExceeded,
      )

      "succeed to refund the remittance amount to the source account, persist a RefundSucceeded event, and then move to a Failed state" in {

        val bankAccountApplication = mock[BankAccountApplication]
        // It does not matter this value if this value is greater than or equal to the remittance amount (the refund amount);
        // It would be great that this value is not equal to the refund amount.
        val sourceBalanceAfterRefund = remittanceAmount + 50
        (bankAccountApplication
          .refund(_: AccountNo, _: TransactionId, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, refundTransactionId, withdrawalTransactionId, remittanceAmount, appRequestContext)
          .returns(Future.successful(RefundResult.Succeeded(sourceBalanceAfterRefund)))

        val (orchestrator, persistenceId, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(refundingToSource, bankAccountApplication)

        orchestrator.runCommand(RefundToSource)

        selfProbe.expectMessage(CompleteTransaction)
        inside(orchestrator.persistenceTestKit.expectNextPersistedType[RefundSucceeded](persistenceId.id)) { event =>
          expect(event.appRequestContext === appRequestContext)
          expect(event.accountNo === sourceAccountNo)
          expect(event.transactionId === refundTransactionId)
          expect(event.withdrawalTransactionId === withdrawalTransactionId)
          expect(event.amount === remittanceAmount)
        }
        inside(orchestrator.getState()) {
          case state: State.Failed =>
            expect(state.appRequestContext === appRequestContext)
            expect(state.sourceAccountNo === sourceAccountNo)
            expect(state.destinationAccountNo === destinationAccountNo)
            expect(state.remittanceAmount === remittanceAmount)
            expect(state.withdrawalTransactionId === withdrawalTransactionId)
            expect(state.depositTransactionId === depositTransactionId)
            expect(state.refundTransactionId === refundTransactionId)
            expect(state.failureReply === ExcessBalance)
        }

      }

      "retry refund several times due to a timeout, succeed the refund, persist a RefundSucceeded event, and then move to a Failed state" in {

        val failureLimit = 3
        val newRefundResult = new SuccessEventually(
          failureLimit,
          Future.successful(RefundResult.Timeout),
          Future.successful(RefundResult.Succeeded(0)),
        )
        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .refund(_: AccountNo, _: TransactionId, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, refundTransactionId, withdrawalTransactionId, remittanceAmount, appRequestContext)
          .repeat(failureLimit + 1)
          .onCall(_ => newRefundResult())

        val (orchestrator, persistenceId, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(refundingToSource, bankAccountApplication)

        // NOTE: Warn logs are crucial; This test should verify warn logs are actually written.
        LoggingTestKit
          .warn("Refund failed due to a timeout.")
          .withMessageContains("RefundingToSource")
          .withOccurrences(failureLimit)
          .expect {

            orchestrator.runCommand(RefundToSource)

            selfProbe.expectMessage(CompleteTransaction)
            inside(orchestrator.persistenceTestKit.expectNextPersistedType[RefundSucceeded](persistenceId.id)) {
              event =>
                expect(event.appRequestContext === appRequestContext)
                expect(event.accountNo === sourceAccountNo)
                expect(event.transactionId === refundTransactionId)
                expect(event.withdrawalTransactionId === withdrawalTransactionId)
                expect(event.amount === remittanceAmount)
            }
            inside(orchestrator.getState()) {
              case state: State.Failed =>
                expect(state.appRequestContext === appRequestContext)
                expect(state.sourceAccountNo === sourceAccountNo)
                expect(state.destinationAccountNo === destinationAccountNo)
                expect(state.remittanceAmount === remittanceAmount)
                expect(state.withdrawalTransactionId === withdrawalTransactionId)
                expect(state.depositTransactionId === depositTransactionId)
                expect(state.refundTransactionId === refundTransactionId)
                expect(state.failureReply === ExcessBalance)
            }

          }

      }

      "retry refund several times due to invalid argument(s), succeed the refund, persist a RefundSucceeded event, and then move to a Failed state" in {
        // NOTE: We might not be able to recover this failure automatically.
        // This test supposes that this failure will recover eventually by human operation.

        val failureLimit = 3
        val newRefundResult = new SuccessEventually(
          failureLimit,
          Future.successful(RefundResult.InvalidArgument),
          Future.successful(RefundResult.Succeeded(0)),
        )
        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .refund(_: AccountNo, _: TransactionId, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, refundTransactionId, withdrawalTransactionId, remittanceAmount, appRequestContext)
          .repeat(failureLimit + 1)
          .onCall(_ => newRefundResult())

        val (orchestrator, persistenceId, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(refundingToSource, bankAccountApplication)

        // NOTE: Error logs are crucial; This test should verify error logs are actually written.
        LoggingTestKit
          .error("Refund failed due to invalid argument(s).")
          .withMessageContains("RefundingToSource")
          .withOccurrences(failureLimit)
          .expect {

            orchestrator.runCommand(RefundToSource)

            selfProbe.expectMessage(CompleteTransaction)
            inside(orchestrator.persistenceTestKit.expectNextPersistedType[RefundSucceeded](persistenceId.id)) {
              event =>
                expect(event.appRequestContext === appRequestContext)
                expect(event.accountNo === sourceAccountNo)
                expect(event.transactionId === refundTransactionId)
                expect(event.withdrawalTransactionId === withdrawalTransactionId)
                expect(event.amount === remittanceAmount)
            }
            inside(orchestrator.getState()) {
              case state: State.Failed =>
                expect(state.appRequestContext === appRequestContext)
                expect(state.sourceAccountNo === sourceAccountNo)
                expect(state.destinationAccountNo === destinationAccountNo)
                expect(state.remittanceAmount === remittanceAmount)
                expect(state.withdrawalTransactionId === withdrawalTransactionId)
                expect(state.depositTransactionId === depositTransactionId)
                expect(state.refundTransactionId === refundTransactionId)
                expect(state.failureReply === ExcessBalance)
            }

          }

      }

      "retry refund several times due to an unexpected exceptions, succeed the refund, persist a RefundSucceeded event, and then move to a Failed state" in {
        // NOTE: We might not be able to recover this failure automatically.
        // This test supposes that this failure will recover eventually by human operation.

        val failureLimit = 3
        val newRefundResult = new SuccessEventually(
          failureLimit,
          Future.failed(new IllegalStateException("unexpected")),
          Future.successful(RefundResult.Succeeded(0)),
        )
        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .refund(_: AccountNo, _: TransactionId, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, refundTransactionId, withdrawalTransactionId, remittanceAmount, appRequestContext)
          .repeat(failureLimit + 1)
          .onCall(_ => newRefundResult())

        val (orchestrator, persistenceId, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(refundingToSource, bankAccountApplication)

        // NOTE: Error logs are crucial; This test should verify error logs are actually written.
        LoggingTestKit
          .error("Refund failed due to an unexpected exception.")
          .withMessageContains("RefundingToSource")
          .withOccurrences(failureLimit)
          .expect {

            orchestrator.runCommand(RefundToSource)

            selfProbe.expectMessage(CompleteTransaction)
            inside(orchestrator.persistenceTestKit.expectNextPersistedType[RefundSucceeded](persistenceId.id)) {
              event =>
                expect(event.appRequestContext === appRequestContext)
                expect(event.accountNo === sourceAccountNo)
                expect(event.transactionId === refundTransactionId)
                expect(event.withdrawalTransactionId === withdrawalTransactionId)
                expect(event.amount === remittanceAmount)
            }
            inside(orchestrator.getState()) {
              case state: State.Failed =>
                expect(state.appRequestContext === appRequestContext)
                expect(state.sourceAccountNo === sourceAccountNo)
                expect(state.destinationAccountNo === destinationAccountNo)
                expect(state.remittanceAmount === remittanceAmount)
                expect(state.withdrawalTransactionId === withdrawalTransactionId)
                expect(state.depositTransactionId === depositTransactionId)
                expect(state.refundTransactionId === refundTransactionId)
                expect(state.failureReply === ExcessBalance)
            }

          }

      }

      "stop when it receives a Stop command" in {
        verifyThatOrchestratorStopsWhenItReceivesStopCommand(refundingToSource)
      }

      "log unexpected commands as warn" in {

        val verifyLogCommandAsWarn = verifyThatOrchestratorLogsUnexpectedReceivedCommandAsWarn(refundingToSource, _)
        verifyLogCommandAsWarn(Passivate)
        verifyLogCommandAsWarn(WithdrawFromSource)
        verifyLogCommandAsWarn(WithdrawCompleted(WithdrawalResult.Succeeded(0)))
        verifyLogCommandAsWarn(WithdrawFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(DepositToDestination)
        verifyLogCommandAsWarn(DepositCompleted(DepositResult.Succeeded(123)))
        verifyLogCommandAsWarn(DepositFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(CompleteTransaction)

      }

      "not send a Passivate command when it receives no commands in the passivateTimeout" in {
        verifyThatOrchestratorDontSendPassivateToShardAfterPassivateTimeout(refundingToSource)
      }

    }

    "State.Succeeded" should {

      implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
      val sourceAccountNo                               = AccountNo("source")
      val destinationAccountNo                          = AccountNo("destination")
      val remittanceAmount                              = BigInt(100)
      val withdrawalTransactionId                       = Context.DefaultTransactionIdFactory.generate()
      val depositTransactionId                          = Context.DefaultTransactionIdFactory.generate()
      val refundTransactionId                           = Context.DefaultTransactionIdFactory.generate()
      val succeeded = State.Succeeded(
        sourceAccountNo,
        destinationAccountNo,
        remittanceAmount,
        withdrawalTransactionId,
        depositTransactionId,
        refundTransactionId,
      )

      "reply a RemitSucceeded" in {

        val bankAccountApplication = mock[BankAccountApplication]

        val (orchestrator, _, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(succeeded, bankAccountApplication)

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val remitCommand                                  = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, _)
        val result                                        = orchestrator.runCommand(remitCommand)

        selfProbe.expectNoMessage()
        expect(result.reply === RemitSucceeded)
        expect(result.state === succeeded)
        expect(result.hasNoEvents)

      }

      "reply a InlaidArgument if the Remit command has a different sourceAccountNo" in {

        val bankAccountApplication = mock[BankAccountApplication]

        val (orchestrator, _, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(succeeded, bankAccountApplication)

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val remitWithDifferentSourceAccountNo =
          Remit(AccountNo(UUID.randomUUID().toString), destinationAccountNo, remittanceAmount, _)
        val result = orchestrator.runCommand(remitWithDifferentSourceAccountNo)

        selfProbe.expectNoMessage()
        expect(result.reply === InvalidArgument)
        expect(result.state === succeeded)
        expect(result.hasNoEvents)

      }

      "reply a InlaidArgument if the Remit command has a different destinationAccountNo" in {

        val bankAccountApplication = mock[BankAccountApplication]

        val (orchestrator, _, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(succeeded, bankAccountApplication)

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val remitWithDifferentDestinationAccountNo =
          Remit(sourceAccountNo, AccountNo(UUID.randomUUID().toString), remittanceAmount, _)
        val result = orchestrator.runCommand(remitWithDifferentDestinationAccountNo)

        selfProbe.expectNoMessage()
        expect(result.reply === InvalidArgument)
        expect(result.state === succeeded)
        expect(result.hasNoEvents)

      }

      "reply a InlaidArgument if the Remit command has a different remittance amount" in {

        val bankAccountApplication = mock[BankAccountApplication]

        val (orchestrator, _, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(succeeded, bankAccountApplication)

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val remitWithDifferentRemittanceAmount =
          Remit(sourceAccountNo, destinationAccountNo, remittanceAmount + 1, _)
        val result = orchestrator.runCommand(remitWithDifferentRemittanceAmount)

        selfProbe.expectNoMessage()
        expect(result.reply === InvalidArgument)
        expect(result.state === succeeded)
        expect(result.hasNoEvents)

      }

      "stop when it receives a Stop command" in {
        verifyThatOrchestratorStopsWhenItReceivesStopCommand(succeeded)
      }

      "log unexpected commands as warn" in {

        val verifyLogCommandAsWarn = verifyThatOrchestratorLogsUnexpectedReceivedCommandAsWarn(succeeded, _)
        verifyLogCommandAsWarn(WithdrawFromSource)
        verifyLogCommandAsWarn(WithdrawCompleted(WithdrawalResult.Succeeded(0)))
        verifyLogCommandAsWarn(WithdrawFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(DepositToDestination)
        verifyLogCommandAsWarn(DepositCompleted(DepositResult.Succeeded(123)))
        verifyLogCommandAsWarn(DepositFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(RefundToSource)
        verifyLogCommandAsWarn(RefundCompleted(RefundResult.Succeeded(456)))
        verifyLogCommandAsWarn(RefundFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(RefundFailedWithUnexpectedException(new IllegalStateException()))

      }

      "send a Passivate command to the shard when it receives no commands in the passivateTimeout" in {
        verifyThatOrchestratorSendPassivateToShardAfterPassivateTimeout(succeeded)
      }

    }

    "State.Failed" should {

      implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
      val sourceAccountNo                               = AccountNo("source")
      val destinationAccountNo                          = AccountNo("destination")
      val remittanceAmount                              = BigInt(100)
      val withdrawalTransactionId                       = Context.DefaultTransactionIdFactory.generate()
      val depositTransactionId                          = Context.DefaultTransactionIdFactory.generate()
      val refundTransactionId                           = Context.DefaultTransactionIdFactory.generate()
      def failedWithFailureReply(failureReply: RemitFailed): State.Failed = {
        State.Failed(
          sourceAccountNo,
          destinationAccountNo,
          remittanceAmount,
          withdrawalTransactionId,
          depositTransactionId,
          refundTransactionId,
          failureReply,
        )
      }

      "reply a ShortBalance" in {

        val failed = failedWithFailureReply(ShortBalance)

        val bankAccountApplication = mock[BankAccountApplication]

        val (orchestrator, _, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(failed, bankAccountApplication)

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val remitCommand                                  = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, _)
        val result                                        = orchestrator.runCommand(remitCommand)

        selfProbe.expectNoMessage()
        expect(result.reply === ShortBalance)
        expect(result.state === failed)
        expect(result.hasNoEvents)

      }

      "reply a ExcessBalance" in {

        val failed = failedWithFailureReply(ExcessBalance)

        val bankAccountApplication = mock[BankAccountApplication]

        val (orchestrator, _, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(failed, bankAccountApplication)

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val remitCommand                                  = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, _)
        val result                                        = orchestrator.runCommand(remitCommand)

        selfProbe.expectNoMessage()
        expect(result.reply === ExcessBalance)
        expect(result.state === failed)
        expect(result.hasNoEvents)

      }

      "reply a InlaidArgument if the Remit command has a different sourceAccountNo" in {

        val failed = failedWithFailureReply(ShortBalance)

        val bankAccountApplication = mock[BankAccountApplication]

        val (orchestrator, _, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(failed, bankAccountApplication)

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val remitWithDifferentSourceAccountNo =
          Remit(AccountNo(UUID.randomUUID().toString), destinationAccountNo, remittanceAmount, _)
        val result = orchestrator.runCommand(remitWithDifferentSourceAccountNo)

        selfProbe.expectNoMessage()
        expect(result.reply === InvalidArgument)
        expect(result.state === failed)
        expect(result.hasNoEvents)

      }

      "reply a InlaidArgument if the Remit command has a different destinationAccountNo" in {

        val failed = failedWithFailureReply(ShortBalance)

        val bankAccountApplication = mock[BankAccountApplication]

        val (orchestrator, _, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(failed, bankAccountApplication)

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val remitWithDifferentDestinationAccountNo =
          Remit(sourceAccountNo, AccountNo(UUID.randomUUID().toString), remittanceAmount, _)
        val result = orchestrator.runCommand(remitWithDifferentDestinationAccountNo)

        selfProbe.expectNoMessage()
        expect(result.reply === InvalidArgument)
        expect(result.state === failed)
        expect(result.hasNoEvents)

      }

      "reply a InlaidArgument if the Remit command has a different remittance amount" in {

        val failed = failedWithFailureReply(ShortBalance)

        val bankAccountApplication = mock[BankAccountApplication]

        val (orchestrator, _, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(failed, bankAccountApplication)

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val remitWithDifferentRemittanceAmount =
          Remit(sourceAccountNo, destinationAccountNo, remittanceAmount + 1, _)
        val result = orchestrator.runCommand(remitWithDifferentRemittanceAmount)

        selfProbe.expectNoMessage()
        expect(result.reply === InvalidArgument)
        expect(result.state === failed)
        expect(result.hasNoEvents)

      }

      "stop when it receives a Stop command" in {
        val failed = failedWithFailureReply(ShortBalance)
        verifyThatOrchestratorStopsWhenItReceivesStopCommand(failed)
      }

      "log unexpected commands as warn" in {

        val failed                 = failedWithFailureReply(ShortBalance)
        val verifyLogCommandAsWarn = verifyThatOrchestratorLogsUnexpectedReceivedCommandAsWarn(failed, _)
        verifyLogCommandAsWarn(WithdrawFromSource)
        verifyLogCommandAsWarn(WithdrawCompleted(WithdrawalResult.Succeeded(0)))
        verifyLogCommandAsWarn(WithdrawFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(DepositToDestination)
        verifyLogCommandAsWarn(DepositCompleted(DepositResult.Succeeded(123)))
        verifyLogCommandAsWarn(DepositFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(RefundToSource)
        verifyLogCommandAsWarn(RefundCompleted(RefundResult.Succeeded(456)))
        verifyLogCommandAsWarn(RefundFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(RefundFailedWithUnexpectedException(new IllegalStateException()))

      }

      "send a Passivate command to the shard when it receives no commands in the passivateTimeout" in {
        val failed = failedWithFailureReply(ShortBalance)
        verifyThatOrchestratorSendPassivateToShardAfterPassivateTimeout(failed)
      }

    }

    "EarlyFailed" should {

      implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
      val sourceAccountNo                               = AccountNo("source")
      val destinationAccountNo                          = AccountNo("destination")
      val remittanceAmount                              = BigInt(-1)
      val earlyFailed = State.EarlyFailed(
        sourceAccountNo,
        destinationAccountNo,
        remittanceAmount,
      )

      "always reply a InlaidArgument" in {

        val bankAccountApplication = mock[BankAccountApplication]

        val (orchestrator, _, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(earlyFailed, bankAccountApplication)

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val remitCommand =
          Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, _)
        val result = orchestrator.runCommand(remitCommand)

        selfProbe.expectNoMessage()
        expect(result.reply === InvalidArgument)
        expect(result.state === earlyFailed)
        expect(result.hasNoEvents)

      }

      "stop when it receives a Stop command" in {
        verifyThatOrchestratorStopsWhenItReceivesStopCommand(earlyFailed)
      }

      "log unexpected commands as warn" in {

        val verifyLogCommandAsWarn = verifyThatOrchestratorLogsUnexpectedReceivedCommandAsWarn(earlyFailed, _)
        verifyLogCommandAsWarn(WithdrawFromSource)
        verifyLogCommandAsWarn(WithdrawCompleted(WithdrawalResult.Succeeded(0)))
        verifyLogCommandAsWarn(WithdrawFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(DepositToDestination)
        verifyLogCommandAsWarn(DepositCompleted(DepositResult.Succeeded(123)))
        verifyLogCommandAsWarn(DepositFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(RefundToSource)
        verifyLogCommandAsWarn(RefundCompleted(RefundResult.Succeeded(456)))
        verifyLogCommandAsWarn(RefundFailedWithUnexpectedException(new IllegalStateException()))
        verifyLogCommandAsWarn(RefundFailedWithUnexpectedException(new IllegalStateException()))

      }

      "send a Passivate command to the shard when it receives no commands in the passivateTimeout" in {
        verifyThatOrchestratorSendPassivateToShardAfterPassivateTimeout(earlyFailed)
      }

    }

    "Integration" should {

      "reply a RemitSucceeded" in {

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("source")
        val destinationAccountNo                          = AccountNo("destination")
        val remittanceAmount                              = BigInt(100)

        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .withdraw(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, *, remittanceAmount, appRequestContext)
          .returns(Future.successful(WithdrawalResult.Succeeded(0)))
        (bankAccountApplication
          .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(destinationAccountNo, *, remittanceAmount, appRequestContext)
          .returns(Future.successful(DepositResult.Succeeded(remittanceAmount)))

        val (orchestrator, persistenceId) =
          createEventSourcedBehaviorTestKit(State.Empty(tenant), bankAccountApplication)

        val remitCommand = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, _)
        val result       = orchestrator.runCommand(remitCommand)

        expect(result.reply === RemitSucceeded)
        orchestrator.persistenceTestKit.expectNextPersistedType[TransactionCreated](persistenceId.id)
        orchestrator.persistenceTestKit.expectNextPersistedType[WithdrawalSucceeded](persistenceId.id)
        orchestrator.persistenceTestKit.expectNextPersistedType[DepositSucceeded](persistenceId.id)
        expect(result.state.isInstanceOf[State.Succeeded])

      }

      "reply a InvalidArgument if the source is the same as the destination" in {

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("account123")
        val destinationAccountNo                          = sourceAccountNo
        val remittanceAmount                              = BigInt(100)

        val bankAccountApplication = mock[BankAccountApplication]

        val (orchestrator, _) =
          createEventSourcedBehaviorTestKit(State.Empty(tenant), bankAccountApplication)

        val remitCommand = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, _)
        val result       = orchestrator.runCommand(remitCommand)

        expect(result.reply === InvalidArgument)
        result.eventOfType[InvalidRemittanceRequested]
        expect(result.state.isInstanceOf[State.EarlyFailed])

      }

      "reply a InvalidArgument if the given remittance amount is negative" in {

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("source")
        val destinationAccountNo                          = AccountNo("destination")
        val remittanceAmount                              = BigInt(-1)

        val bankAccountApplication = mock[BankAccountApplication]

        val (orchestrator, _) =
          createEventSourcedBehaviorTestKit(State.Empty(tenant), bankAccountApplication)

        val remitCommand = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, _)
        val result       = orchestrator.runCommand(remitCommand)

        expect(result.reply === InvalidArgument)
        result.eventOfType[InvalidRemittanceRequested]
        expect(result.state.isInstanceOf[State.EarlyFailed])

      }

      "reply a ShortBalance if a withdrawal fails due to a short balance" in {

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("source")
        val destinationAccountNo                          = AccountNo("destination")
        val remittanceAmount                              = BigInt(100)

        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .withdraw(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, *, remittanceAmount, appRequestContext)
          .returns(Future.successful(WithdrawalResult.ShortBalance))

        val (orchestrator, persistenceId) =
          createEventSourcedBehaviorTestKit(State.Empty(tenant), bankAccountApplication)

        val remitCommand = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, _)
        val result       = orchestrator.runCommand(remitCommand)

        expect(result.reply === ShortBalance)
        orchestrator.persistenceTestKit.expectNextPersistedType[TransactionCreated](persistenceId.id)
        orchestrator.persistenceTestKit.expectNextPersistedType[BalanceShorted](persistenceId.id)
        expect(result.state.isInstanceOf[State.Failed])

      }

      "reply a ExcessBalance if a deposit fails due to an excess balance" in {

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("source")
        val destinationAccountNo                          = AccountNo("destination")
        val remittanceAmount                              = BigInt(100)

        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .withdraw(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, *, remittanceAmount, appRequestContext)
          .returns(Future.successful(WithdrawalResult.Succeeded(0)))

        (bankAccountApplication
          .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(destinationAccountNo, *, remittanceAmount, appRequestContext)
          .returns(Future.successful(DepositResult.ExcessBalance))

        (bankAccountApplication
          .refund(_: AccountNo, _: TransactionId, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, *, *, remittanceAmount, appRequestContext)
          .returns(Future.successful(RefundResult.Succeeded(remittanceAmount)))

        val (orchestrator, persistenceId) =
          createEventSourcedBehaviorTestKit(State.Empty(tenant), bankAccountApplication)

        val remitCommand = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, _)
        val result       = orchestrator.runCommand(remitCommand)

        expect(result.reply === ExcessBalance)
        orchestrator.persistenceTestKit.expectNextPersistedType[TransactionCreated](persistenceId.id)
        orchestrator.persistenceTestKit.expectNextPersistedType[WithdrawalSucceeded](persistenceId.id)
        orchestrator.persistenceTestKit.expectNextPersistedType[BalanceExceeded](persistenceId.id)
        orchestrator.persistenceTestKit.expectNextPersistedType[RefundSucceeded](persistenceId.id)
        expect(result.state.isInstanceOf[State.Failed])

      }

      "resume the withdrawal automatically when it is recovered" in {

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("source")
        val destinationAccountNo                          = AccountNo("destination")
        val remittanceAmount                              = BigInt(100)
        val withdrawalTransactionId                       = Context.DefaultTransactionIdFactory.generate()
        val depositTransactionId                          = Context.DefaultTransactionIdFactory.generate()
        val refundTransactionId                           = Context.DefaultTransactionIdFactory.generate()
        val withdrawingFromSource = State.WithdrawingFromSource(
          sourceAccountNo,
          destinationAccountNo,
          remittanceAmount,
          withdrawalTransactionId,
          depositTransactionId,
          refundTransactionId,
        )

        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .withdraw(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, withdrawalTransactionId, remittanceAmount, appRequestContext)
          .returns(Future.successful(WithdrawalResult.Succeeded(0)))
        (bankAccountApplication
          .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(destinationAccountNo, depositTransactionId, remittanceAmount, appRequestContext)
          .returns(Future.successful(DepositResult.Succeeded(remittanceAmount)))

        val (orchestrator, persistenceId) =
          createEventSourcedBehaviorTestKit(withdrawingFromSource, bankAccountApplication)

        // Do not emit the WithdrawFromSource.
        // The orchestrator's recovery process will emit the command.

        orchestrator.persistenceTestKit.expectNextPersistedType[WithdrawalSucceeded](persistenceId.id)
        orchestrator.persistenceTestKit.expectNextPersistedType[DepositSucceeded](persistenceId.id)
        eventually {
          expect(orchestrator.getState().isInstanceOf[State.Succeeded])
        }

      }

      "resume the depositing automatically when it is recovered" in {

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("source")
        val destinationAccountNo                          = AccountNo("destination")
        val remittanceAmount                              = BigInt(100)
        val withdrawalTransactionId                       = Context.DefaultTransactionIdFactory.generate()
        val depositTransactionId                          = Context.DefaultTransactionIdFactory.generate()
        val refundTransactionId                           = Context.DefaultTransactionIdFactory.generate()
        val refundingToSource = State.RefundingToSource(
          sourceAccountNo,
          destinationAccountNo,
          remittanceAmount,
          withdrawalTransactionId,
          depositTransactionId,
          refundTransactionId,
          State.RefundingToSource.RefundReason.BalanceExceeded,
        )

        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .refund(_: AccountNo, _: TransactionId, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, refundTransactionId, withdrawalTransactionId, remittanceAmount, appRequestContext)
          .returns(Future.successful(RefundResult.Succeeded(remittanceAmount)))

        val (orchestrator, persistenceId) =
          createEventSourcedBehaviorTestKit(refundingToSource, bankAccountApplication)

        // Do not emit the RefundToSource.
        // The orchestrator's recovery process will emit the command.

        orchestrator.persistenceTestKit.expectNextPersistedType[RefundSucceeded](persistenceId.id)
        eventually {
          expect(orchestrator.getState().isInstanceOf[State.Failed])
        }

      }

      "resume the deposit automatically when it is recovered" in {

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("source")
        val destinationAccountNo                          = AccountNo("destination")
        val remittanceAmount                              = BigInt(100)
        val withdrawalTransactionId                       = Context.DefaultTransactionIdFactory.generate()
        val depositTransactionId                          = Context.DefaultTransactionIdFactory.generate()
        val refundTransactionId                           = Context.DefaultTransactionIdFactory.generate()
        val depositingToDestination = State.DepositingToDestination(
          sourceAccountNo,
          destinationAccountNo,
          remittanceAmount,
          withdrawalTransactionId,
          depositTransactionId,
          refundTransactionId,
        )

        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(destinationAccountNo, depositTransactionId, remittanceAmount, appRequestContext)
          .returns(Future.successful(DepositResult.Succeeded(remittanceAmount)))

        val (orchestrator, persistenceId) =
          createEventSourcedBehaviorTestKit(depositingToDestination, bankAccountApplication)

        // Do not emit the DepositToDestination.
        // The orchestrator's recovery process will emit the command.

        orchestrator.persistenceTestKit.expectNextPersistedType[DepositSucceeded](persistenceId.id)
        eventually {
          expect(orchestrator.getState().isInstanceOf[State.Succeeded])
        }

      }

      "stash a Remit command at a WithdrawingFromSource, process internal commands, and then respond to the Remit command" in {

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("source")
        val destinationAccountNo                          = AccountNo("destination")
        val remittanceAmount                              = BigInt(100)
        val withdrawalTransactionId                       = Context.DefaultTransactionIdFactory.generate()
        val depositTransactionId                          = Context.DefaultTransactionIdFactory.generate()
        val refundTransactionId                           = Context.DefaultTransactionIdFactory.generate()
        val withdrawingFromSource = State.WithdrawingFromSource(
          sourceAccountNo,
          destinationAccountNo,
          remittanceAmount,
          withdrawalTransactionId,
          depositTransactionId,
          refundTransactionId,
        )

        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .withdraw(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, withdrawalTransactionId, remittanceAmount, appRequestContext)
          .returns(Future.successful(WithdrawalResult.Succeeded(0)))
        (bankAccountApplication
          .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(destinationAccountNo, depositTransactionId, remittanceAmount, appRequestContext)
          .returns(Future.successful(DepositResult.Succeeded(remittanceAmount)))

        val (orchestrator, persistenceId, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(withdrawingFromSource, bankAccountApplication)

        // We emulate the orchestrator receiving a Remit command in a WithdrawingFromSource by emitting the command manually.
        // And then, we emulate the orchestrator's internal command processing.
        val replyProbe   = testKit.createTestProbe[RemitReply]()
        val remitCommand = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)
        orchestrator.runCommand(remitCommand)
        orchestrator.runCommand(WithdrawFromSource)

        selfProbe.expectMessage(DepositToDestination)
        orchestrator.runCommand(DepositToDestination)

        selfProbe.expectMessage(CompleteTransaction)
        orchestrator.runCommand(CompleteTransaction)

        replyProbe.expectMessage(RemitSucceeded)
        orchestrator.persistenceTestKit.expectNextPersistedType[WithdrawalSucceeded](persistenceId.id)
        orchestrator.persistenceTestKit.expectNextPersistedType[DepositSucceeded](persistenceId.id)
        expect(orchestrator.getState().isInstanceOf[State.Succeeded])

      }

      "stash a Remit command at a DepositingToDestination, process internal commands, and then respond to the Remit command" in {

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("source")
        val destinationAccountNo                          = AccountNo("destination")
        val remittanceAmount                              = BigInt(100)
        val withdrawalTransactionId                       = Context.DefaultTransactionIdFactory.generate()
        val depositTransactionId                          = Context.DefaultTransactionIdFactory.generate()
        val refundTransactionId                           = Context.DefaultTransactionIdFactory.generate()
        val depositingToDestination = State.DepositingToDestination(
          sourceAccountNo,
          destinationAccountNo,
          remittanceAmount,
          withdrawalTransactionId,
          depositTransactionId,
          refundTransactionId,
        )

        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(destinationAccountNo, depositTransactionId, remittanceAmount, appRequestContext)
          .returns(Future.successful(DepositResult.Succeeded(remittanceAmount)))

        val (orchestrator, persistenceId, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(depositingToDestination, bankAccountApplication)

        // We emulate the orchestrator receiving a Remit command in a DepositingToDestination by emitting the command manually.
        // And then, we emulate we emulate the orchestrator's internal command processing.
        val replyProbe   = testKit.createTestProbe[RemitReply]()
        val remitCommand = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)
        orchestrator.runCommand(remitCommand)
        orchestrator.runCommand(DepositToDestination)

        selfProbe.expectMessage(CompleteTransaction)
        orchestrator.runCommand(CompleteTransaction)

        replyProbe.expectMessage(RemitSucceeded)
        orchestrator.persistenceTestKit.expectNextPersistedType[DepositSucceeded](persistenceId.id)
        expect(orchestrator.getState().isInstanceOf[State.Succeeded])

      }

      "stash a Remit command at a RefundingToSource, process internal commands, and then respond to the Remit command" in {

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("source")
        val destinationAccountNo                          = AccountNo("destination")
        val remittanceAmount                              = BigInt(100)
        val withdrawalTransactionId                       = Context.DefaultTransactionIdFactory.generate()
        val depositTransactionId                          = Context.DefaultTransactionIdFactory.generate()
        val refundTransactionId                           = Context.DefaultTransactionIdFactory.generate()
        val refundingToSource = State.RefundingToSource(
          sourceAccountNo,
          destinationAccountNo,
          remittanceAmount,
          withdrawalTransactionId,
          depositTransactionId,
          refundTransactionId,
          State.RefundingToSource.RefundReason.BalanceExceeded,
        )

        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .refund(_: AccountNo, _: TransactionId, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, refundTransactionId, withdrawalTransactionId, remittanceAmount, appRequestContext)
          .returns(Future.successful(RefundResult.Succeeded(remittanceAmount)))

        val (orchestrator, persistenceId, selfProbe) =
          createEventSourcedBehaviorTestKitWithSelfProbe(refundingToSource, bankAccountApplication)

        // We emulate the orchestrator receiving a Remit command in a RefundingToSource by emitting the command manually.
        // And then, we emulate we emulate the orchestrator's internal command processing.
        val replyProbe   = testKit.createTestProbe[RemitReply]()
        val remitCommand = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)
        orchestrator.runCommand(remitCommand)
        orchestrator.runCommand(RefundToSource)

        selfProbe.expectMessage(CompleteTransaction)
        orchestrator.runCommand(CompleteTransaction)

        replyProbe.expectMessage(ExcessBalance)
        orchestrator.persistenceTestKit.expectNextPersistedType[RefundSucceeded](persistenceId.id)
        expect(orchestrator.getState().isInstanceOf[State.Failed])

      }

      "restart while preserving stashed commands if a journal failure occurs, and then resume its behavior" in {

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("source")
        val destinationAccountNo                          = AccountNo("destination")
        val remittanceAmount                              = BigInt(100)

        val bankAccountApplication = mock[BankAccountApplication]
        // Withdrawal will be called twice
        // since the first WithdrawalSucceeded persisting will fail due to a journal failure.
        (bankAccountApplication
          .withdraw(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, *, remittanceAmount, appRequestContext)
          .twice()
          .returns(Future.successful(WithdrawalResult.Succeeded(0)))
        (bankAccountApplication
          .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(destinationAccountNo, *, remittanceAmount, appRequestContext)
          .returns(Future.successful(DepositResult.Succeeded(remittanceAmount)))

        val (orchestrator, persistenceId) =
          createEventSourcedBehaviorTestKit(State.Empty(tenant), bankAccountApplication)

        // Emulate a journal failure
        object SecondOperationFailurePolicy extends EventStorage.JournalPolicies.PolicyType {
          @SuppressWarnings(Array("org.wartremover.warts.Var"))
          private var count = 0
          final class CustomFailure extends RuntimeException
          override def tryProcess(persistenceId: String, processingUnit: JournalOperation): ProcessingResult = {
            processingUnit match {
              case _: ReadEvents =>
                ProcessingSuccess
              case _: WriteEvents =>
                count += 1
                if (count === 2) {
                  // The second journal operation will fail.
                  // This operation is the write operation of the first withdrawal result.
                  StorageFailure(new CustomFailure())
                } else {
                  ProcessingSuccess
                }
              case ReadSeqNum =>
                ProcessingSuccess
              case _: DeleteEvents =>
                ProcessingSuccess
            }
          }
        }
        orchestrator.persistenceTestKit.withPolicy(SecondOperationFailurePolicy)

        val remitCommand = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, _)
        val result       = orchestrator.runCommand(remitCommand)

        expect(result.reply === RemitSucceeded)
        orchestrator.persistenceTestKit.expectNextPersistedType[TransactionCreated](persistenceId.id)
        orchestrator.persistenceTestKit.expectNextPersistedType[WithdrawalSucceeded](persistenceId.id)
        orchestrator.persistenceTestKit.expectNextPersistedType[DepositSucceeded](persistenceId.id)
        expect(result.state.isInstanceOf[State.Succeeded])

      }

    }

    "Serialization" should {

      val serializationTestKit = new SerializationTestKit(system)

      "serialize a Remit command" in {

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("source")
        val destinationAccountNo                          = AccountNo("destination")
        val remittanceAmount                              = BigInt(100)
        val replyProbe                                    = testKit.createTestProbe[RemitReply]()
        val remitCommand                                  = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)
        serializationTestKit.verifySerialization(remitCommand, serializationSettings.verifyEquality)

      }

      "serialize a Stop command" in {

        serializationTestKit.verifySerialization(Stop, serializationSettings.verifyEquality)

      }

    }

  }

}
