package myapp.application.account

import akka.actor.testkit.typed.scaladsl.LoggingTestKit
import akka.actor.typed.{ ActorRef, Behavior }
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.testkit.scaladsl.PersistenceTestKit
import akka.persistence.testkit.{ PersistenceTestKitPlugin, PersistenceTestKitSnapshotPlugin }
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
import org.scalatest.{ BeforeAndAfterEach, Inside }

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

object RemittanceOrchestratorBehaviorSpec {
  import RemittanceOrchestratorBehavior._

  private val config = {
    val appConfig = ConfigFactory.load("application-test")
    PersistenceTestKitPlugin.config
      .withFallback(PersistenceTestKitSnapshotPlugin.config)
      .withFallback(appConfig)
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
    with BeforeAndAfterEach
    with Inside
    with MockFactory {

  import RemittanceOrchestratorBehavior._
  import RemittanceOrchestratorBehaviorSpec._

  private val persistenceTestKit = PersistenceTestKit(system)

  override def beforeEach(): Unit = {
    super.beforeEach()
    persistenceTestKit.clearAll()
  }

  private def expectStateEventually[T: ClassTag](orchestrator: ActorRef[Command]): T = {
    val stateProbe = testKit.createTestProbe[InspectStateReply]()
    eventually {
      orchestrator ! InspectState(stateProbe.ref)
      val reply = stateProbe.expectMessageType[InspectStateReply]
      reply.state match {
        case expectedState: T => expectedState
        case unexpectedState =>
          val classTag = implicitly[ClassTag[T]]
          fail(s"Expected an instance of ${classTag.getClass.getName}, but got ${unexpectedState.getClass.getName}")
      }
    }
  }

  private def createBehavior(
      initialState: State,
      bankAccountApplication: BankAccountApplication,
      shard: ActorRef[ClusterSharding.ShardCommand] = createTestProbe[ClusterSharding.ShardCommand]().ref,
      transactionIdFactory: Context.TransactionIdFactory = Context.DefaultTransactionIdFactory,
      callbacks: Context.Callbacks = Context.DefaultCallbacks,
  ): (PersistenceId, Behavior[Command]) = {
    val entityId      = EntityId(UUID.randomUUID().toString)
    val persistenceId = PersistenceId(typeKey(tenant).name, entityId.value)
    val behavior = RemittanceOrchestratorBehavior(
      entityId = entityId,
      persistenceId = persistenceId,
      settings,
      bankAccountApplication,
      shard,
      initialState,
      transactionIdFactory,
      callbacks,
    )
    (persistenceId, behavior)
  }

  "RemittanceOrchestratorBehavior" when {

    def verifyThatOrchestratorStopsWhenItReceivesStopCommand(state: State): Unit = {
      val bankAccountApplication = mock[BankAccountApplication]
      val callbacks              = mock[Context.Callbacks]
      // We should not delegate to the DefaultCallbacks
      // since the DefaultCallbacks might send an internal command that depends on BankAccountApplication.
      (callbacks.onRecoveryCompleted _)
        .expects(*, state)
        .returns(())

      val (_, orchestratorBehavior) =
        createBehavior(state, bankAccountApplication, callbacks = callbacks)
      val orchestrator = spawn(orchestratorBehavior)

      val probe = createTestProbe[Command]()
      orchestrator ! Stop
      probe.expectTerminated(orchestrator)
    }

    def verifyThatOrchestratorLogsUnexpectedReceivedCommandAsWarn(state: State, command: Command): Unit = {
      val bankAccountApplication = mock[BankAccountApplication]
      val callbacks              = mock[Context.Callbacks]
      // We should not delegate to the DefaultCallbacks
      // since the DefaultCallbacks might send an internal command that depends on BankAccountApplication.
      (callbacks.onRecoveryCompleted _)
        .expects(*, state)
        .returns(())

      val (_, orchestratorBehavior) =
        createBehavior(state, bankAccountApplication, callbacks = callbacks)
      val orchestrator = spawn(orchestratorBehavior)

      LoggingTestKit
        .warn(s"Unexpected command ${command.toString}")
        .expect {
          orchestrator ! command
        }
    }

    def verifyThatOrchestratorSendPassivateToShardAfterPassivateTimeout(state: State): Unit = {
      val bankAccountApplication = mock[BankAccountApplication]
      val callbacks              = mock[Context.Callbacks]
      // We should delegate to the DefaultCallbacks
      // since the DefaultCallbacks will send a Passivate command, which we want to verify.
      (callbacks.onRecoveryCompleted _)
        .expects(*, state)
        .onCall(Context.DefaultCallbacks.onRecoveryCompleted _)

      val shardProbe = createTestProbe[ClusterSharding.ShardCommand]()
      val (_, orchestratorBehavior) =
        createBehavior(state, bankAccountApplication, shard = shardProbe.ref, callbacks = callbacks)
      val orchestrator = spawn(orchestratorBehavior)

      // Wait enough longer than the passivateTimeout.
      val timeout = settings.passivateTimeout * 2
      shardProbe.expectMessage(timeout, ClusterSharding.Passivate(orchestrator))
    }

    def verifyThatOrchestratorDontSendPassivateToShardAfterPassivateTimeout(state: State): Unit = {
      val bankAccountApplication = mock[BankAccountApplication]
      val callbacks              = mock[Context.Callbacks]
      // We should not delegate to the DefaultCallbacks
      // since the DefaultCallbacks send an internal command to the orchestrator.
      (callbacks.onRecoveryCompleted _)
        .expects(*, state)
        .returns(())

      val shardProbe = createTestProbe[ClusterSharding.ShardCommand]()
      val (_, orchestratorBehavior) =
        createBehavior(state, bankAccountApplication, shard = shardProbe.ref, callbacks = callbacks)
      val _ = spawn(orchestratorBehavior)

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

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, emptyState)
          .returns(())
        (callbacks.onTransactionCreated _)
          .expects(where { (_, oldState, newState, _) =>
            oldState === emptyState &&
            newState.isInstanceOf[State.WithdrawingFromSource]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(
            emptyState,
            bankAccountApplication,
            transactionIdFactory = transactionIdFactory,
            callbacks = callbacks,
          )
        val orchestrator = spawn(orchestratorBehavior)

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("source")
        val destinationAccountNo                          = AccountNo("destination")
        val remittanceAmount                              = BigInt(100)
        val replyProbe                                    = testKit.createTestProbe[RemitReply]()
        val remitCommand                                  = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)
        orchestrator ! remitCommand

        replyProbe.expectNoMessage()
        inside(persistenceTestKit.expectNextPersistedType[TransactionCreated](persistenceId.id)) { event =>
          expect(event.appRequestContext === appRequestContext)
          expect(event.sourceAccountNo === sourceAccountNo)
          expect(event.destinationAccountNo === destinationAccountNo)
          expect(event.amount === remittanceAmount)
          expect(event.withdrawalTransactionId === expectedWithdrawalTransactionId)
          expect(event.depositTransactionId === expectedDepositTransactionId)
          expect(event.refundTransactionId === expectedRefundTransactionId)
        }
        inside(expectStateEventually[State.WithdrawingFromSource](orchestrator)) { state =>
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

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, emptyState)
        (callbacks.onInvalidRemitRequested _)
          .expects(where { (_, oldState, newState, _) =>
            oldState === emptyState &&
            newState.isInstanceOf[State.EarlyFailed]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(emptyState, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("account")
        val destinationAccountNo                          = sourceAccountNo
        val remittanceAmount                              = BigInt(100)
        val replyProbe                                    = testKit.createTestProbe[RemitReply]()
        val remitCommand                                  = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)
        orchestrator ! remitCommand

        replyProbe.expectNoMessage()
        inside(persistenceTestKit.expectNextPersistedType[InvalidRemittanceRequested](persistenceId.id)) { event =>
          expect(event.appRequestContext === appRequestContext)
          expect(event.sourceAccountNo === sourceAccountNo)
          expect(event.destinationAccountNo === destinationAccountNo)
          expect(event.amount === remittanceAmount)
        }
        inside(expectStateEventually[State.EarlyFailed](orchestrator)) { state =>
          expect(state.appRequestContext === appRequestContext)
          expect(state.sourceAccountNo === sourceAccountNo)
          expect(state.destinationAccountNo === destinationAccountNo)
          expect(state.remittanceAmount === remittanceAmount)
        }

      }

      "reject a Remit command with a negative remittance amount, persist a InvalidRemittanceRequested event, and then move to a EarlyFailed state" in {

        val bankAccountApplication = mock[BankAccountApplication]

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, emptyState)
        (callbacks.onInvalidRemitRequested _)
          .expects(where { (_, oldState, newState, _) =>
            oldState === emptyState &&
            newState.isInstanceOf[State.EarlyFailed]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(emptyState, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("source")
        val destinationAccountNo                          = AccountNo("destination")
        val remittanceAmount                              = BigInt(-1)
        val replyProbe                                    = testKit.createTestProbe[RemitReply]()
        val remitCommand                                  = Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)
        orchestrator ! remitCommand

        replyProbe.expectNoMessage()
        inside(persistenceTestKit.expectNextPersistedType[InvalidRemittanceRequested](persistenceId.id)) { event =>
          expect(event.appRequestContext === appRequestContext)
          expect(event.sourceAccountNo === sourceAccountNo)
          expect(event.destinationAccountNo === destinationAccountNo)
          expect(event.amount === remittanceAmount)
        }
        inside(expectStateEventually[State.EarlyFailed](orchestrator)) { state =>
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

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, withdrawingFromSource)
          .returns(())
        (callbacks.onWithdrawSucceeded _)
          .expects(where { (_, oldState, newState) =>
            oldState === withdrawingFromSource &&
            newState.isInstanceOf[State.DepositingToDestination]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(withdrawingFromSource, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        orchestrator ! WithdrawFromSource

        inside(persistenceTestKit.expectNextPersistedType[WithdrawalSucceeded](persistenceId.id)) { event =>
          expect(event.appRequestContext === appRequestContext)
          expect(event.accountNo === sourceAccountNo)
          expect(event.transactionId === withdrawalTransactionId)
          expect(event.amount === remittanceAmount)
        }
        inside(expectStateEventually[State.DepositingToDestination](orchestrator)) { state =>
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

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, withdrawingFromSource)
          .returns(())
        (callbacks.onWithdrawFailed _)
          .expects(where { (_, oldState, newState) =>
            oldState === withdrawingFromSource &&
            newState.isInstanceOf[State.Failed]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(withdrawingFromSource, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        orchestrator ! WithdrawFromSource

        inside(persistenceTestKit.expectNextPersistedType[BalanceShorted](persistenceId.id)) { event =>
          expect(event.appRequestContext === appRequestContext)
          expect(event.accountNo === sourceAccountNo)
          expect(event.transactionId === withdrawalTransactionId)
          expect(event.amount === remittanceAmount)
        }
        inside(expectStateEventually[State.Failed](orchestrator)) { state =>
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

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, withdrawingFromSource)
          .returns(())
        (callbacks.onWithdrawSucceeded _)
          .expects(where { (_, oldState, newState) =>
            oldState === withdrawingFromSource &&
            newState.isInstanceOf[State.DepositingToDestination]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(withdrawingFromSource, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        // NOTE: Warn logs are crucial; This test should verify warn logs are actually written.
        LoggingTestKit
          .warn("Withdrawal failed due to a timeout.")
          .withMessageContains("WithdrawingFromSource")
          .withOccurrences(failureLimit)
          .expect {

            orchestrator ! WithdrawFromSource

            inside(persistenceTestKit.expectNextPersistedType[WithdrawalSucceeded](persistenceId.id)) { event =>
              expect(event.appRequestContext === appRequestContext)
              expect(event.accountNo === sourceAccountNo)
              expect(event.transactionId === withdrawalTransactionId)
              expect(event.amount === remittanceAmount)
            }
            inside(expectStateEventually[State.DepositingToDestination](orchestrator)) { state =>
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

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, withdrawingFromSource)
          .returns(())
        (callbacks.onWithdrawSucceeded _)
          .expects(where { (_, oldState, newState) =>
            oldState === withdrawingFromSource &&
            newState.isInstanceOf[State.DepositingToDestination]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(withdrawingFromSource, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        // NOTE: Error logs are crucial; This test should verify error logs are actually written.
        LoggingTestKit
          .error("Withdrawal failed with an unexpected exception.")
          .withMessageContains("WithdrawingFromSource")
          .withOccurrences(failureLimit)
          .expect {

            orchestrator ! WithdrawFromSource

            inside(persistenceTestKit.expectNextPersistedType[WithdrawalSucceeded](persistenceId.id)) { event =>
              expect(event.appRequestContext === appRequestContext)
              expect(event.accountNo === sourceAccountNo)
              expect(event.transactionId === withdrawalTransactionId)
              expect(event.amount === remittanceAmount)
            }
            inside(expectStateEventually[State.DepositingToDestination](orchestrator)) { state =>
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

      "resume the withdrawal automatically when it is recovered" in {

        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .withdraw(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, withdrawalTransactionId, remittanceAmount, appRequestContext)
          .returns(Future.successful(WithdrawalResult.Succeeded(100)))

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, withdrawingFromSource)
          .onCall(Context.DefaultCallbacks.onRecoveryCompleted _) // Should delegate to the DefaultCallbacks.
        (callbacks.onWithdrawSucceeded _)
          .expects(where { (_, oldState, newState) =>
            oldState === withdrawingFromSource &&
            newState.isInstanceOf[State.DepositingToDestination]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(withdrawingFromSource, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        // Don't send WithdrawFromSource to the orchestrator.
        // The orchestrator resumes the withdrawal automatically.

        persistenceTestKit.expectNextPersistedType[WithdrawalSucceeded](persistenceId.id)
        expectStateEventually[State.DepositingToDestination](orchestrator)

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
        // It does not matter this value if this value is greater than or equal to the remittance amount (the eposit amount);
        // It would be great that this value is not equal to the deposit amount.
        val destinationBalanceAfterDeposit = remittanceAmount + 50
        (bankAccountApplication
          .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(destinationAccountNo, depositTransactionId, remittanceAmount, appRequestContext)
          .returns(Future.successful(DepositResult.Succeeded(destinationBalanceAfterDeposit)))

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, depositingToDestination)
          .returns(())
        (callbacks.onDepositSucceeded _)
          .expects(where { (_, oldState, newState) =>
            oldState === depositingToDestination &&
            newState.isInstanceOf[State.Succeeded]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(depositingToDestination, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        orchestrator ! DepositToDestination

        inside(persistenceTestKit.expectNextPersistedType[DepositSucceeded](persistenceId.id)) { event =>
          expect(event.appRequestContext === appRequestContext)
          expect(event.accountNo === destinationAccountNo)
          expect(event.transactionId === depositTransactionId)
          expect(event.amount === remittanceAmount)
        }
        inside(expectStateEventually[State.Succeeded](orchestrator)) { state =>
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

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, depositingToDestination)
          .returns(())
        (callbacks.onDepositFailed _)
          .expects(where { (_, oldState, newState) =>
            oldState === depositingToDestination &&
            newState.isInstanceOf[State.RefundingToSource]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(depositingToDestination, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        orchestrator ! DepositToDestination

        inside(persistenceTestKit.expectNextPersistedType[BalanceExceeded](persistenceId.id)) { event =>
          expect(event.appRequestContext === appRequestContext)
          expect(event.accountNo === destinationAccountNo)
          expect(event.transactionId === depositTransactionId)
          expect(event.amount === remittanceAmount)
        }
        inside(expectStateEventually[State.RefundingToSource](orchestrator)) { state =>
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

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, depositingToDestination)
          .returns(())
        (callbacks.onDepositSucceeded _)
          .expects(where { (_, oldState, newState) =>
            oldState === depositingToDestination &&
            newState.isInstanceOf[State.Succeeded]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(depositingToDestination, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        // NOTE: Warn logs are crucial; This test should verify warn logs are actually written.
        LoggingTestKit
          .warn("Deposit failed due to a timeout.")
          .withMessageContains("DepositingToDestination")
          .withOccurrences(failureLimit)
          .expect {

            orchestrator ! DepositToDestination

            inside(persistenceTestKit.expectNextPersistedType[DepositSucceeded](persistenceId.id)) { event =>
              expect(event.appRequestContext === appRequestContext)
              expect(event.accountNo === destinationAccountNo)
              expect(event.transactionId === depositTransactionId)
              expect(event.amount === remittanceAmount)
            }
            inside(expectStateEventually[State.Succeeded](orchestrator)) { state =>
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

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, depositingToDestination)
          .returns(())
        (callbacks.onDepositSucceeded _)
          .expects(where { (_, oldState, newState) =>
            oldState === depositingToDestination &&
            newState.isInstanceOf[State.Succeeded]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(depositingToDestination, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        // NOTE: Error logs are crucial; This test should verify error logs are actually written.
        LoggingTestKit
          .error("Deposit failed with an unexpected exception.")
          .withMessageContains("DepositingToDestination")
          .withOccurrences(failureLimit)
          .expect {

            orchestrator ! DepositToDestination

            inside(persistenceTestKit.expectNextPersistedType[DepositSucceeded](persistenceId.id)) { event =>
              expect(event.appRequestContext === appRequestContext)
              expect(event.accountNo === destinationAccountNo)
              expect(event.transactionId === depositTransactionId)
              expect(event.amount === remittanceAmount)
            }
            inside(expectStateEventually[State.Succeeded](orchestrator)) { state =>
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

      "resume the deposit automatically when it is recovered" in {

        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .deposit(_: AccountNo, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(destinationAccountNo, depositTransactionId, remittanceAmount, appRequestContext)
          .returns(Future.successful(DepositResult.Succeeded(100)))

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, depositingToDestination)
          .onCall(Context.DefaultCallbacks.onRecoveryCompleted _) // Should delegate to the DefaultCallbacks.
        (callbacks.onDepositSucceeded _)
          .expects(where { (_, oldState, newState) =>
            oldState === depositingToDestination &&
            newState.isInstanceOf[State.Succeeded]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(depositingToDestination, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        // Don't send DepositToDestination to the orchestrator.
        // The orchestrator resumes the deposit automatically.

        persistenceTestKit.expectNextPersistedType[DepositSucceeded](persistenceId.id)
        expectStateEventually[State.Succeeded](orchestrator)

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

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, refundingToSource)
          .returns(())
        (callbacks.onRefundSucceeded _)
          .expects(where { (_, oldState, newState) =>
            oldState === refundingToSource &&
            newState.isInstanceOf[State.Failed]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(refundingToSource, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        orchestrator ! RefundToSource

        inside(persistenceTestKit.expectNextPersistedType[RefundSucceeded](persistenceId.id)) { event =>
          expect(event.appRequestContext === appRequestContext)
          expect(event.accountNo === sourceAccountNo)
          expect(event.transactionId === refundTransactionId)
          expect(event.withdrawalTransactionId === withdrawalTransactionId)
          expect(event.amount === remittanceAmount)
        }
        inside(expectStateEventually[State.Failed](orchestrator)) { state =>
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

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, refundingToSource)
          .returns(())
        (callbacks.onRefundSucceeded _)
          .expects(where { (_, oldState, newState) =>
            oldState === refundingToSource &&
            newState.isInstanceOf[State.Failed]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(refundingToSource, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        // NOTE: Warn logs are crucial; This test should verify warn logs are actually written.
        LoggingTestKit
          .warn("Refund failed due to a timeout.")
          .withMessageContains("RefundingToSource")
          .withOccurrences(failureLimit)
          .expect {

            orchestrator ! RefundToSource

            inside(persistenceTestKit.expectNextPersistedType[RefundSucceeded](persistenceId.id)) { event =>
              expect(event.appRequestContext === appRequestContext)
              expect(event.accountNo === sourceAccountNo)
              expect(event.transactionId === refundTransactionId)
              expect(event.withdrawalTransactionId === withdrawalTransactionId)
              expect(event.amount === remittanceAmount)
            }
            inside(expectStateEventually[State.Failed](orchestrator)) { state =>
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

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, refundingToSource)
          .returns(())
        (callbacks.onRefundSucceeded _)
          .expects(where { (_, oldState, newState) =>
            oldState === refundingToSource &&
            newState.isInstanceOf[State.Failed]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(refundingToSource, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        // NOTE: Error logs are crucial; This test should verify error logs are actually written.
        LoggingTestKit
          .error("Refund failed due to invalid argument(s).")
          .withMessageContains("RefundingToSource")
          .withOccurrences(failureLimit)
          .expect {

            orchestrator ! RefundToSource

            inside(persistenceTestKit.expectNextPersistedType[RefundSucceeded](persistenceId.id)) { event =>
              expect(event.appRequestContext === appRequestContext)
              expect(event.accountNo === sourceAccountNo)
              expect(event.transactionId === refundTransactionId)
              expect(event.withdrawalTransactionId === withdrawalTransactionId)
              expect(event.amount === remittanceAmount)
            }
            inside(expectStateEventually[State.Failed](orchestrator)) { state =>
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

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, refundingToSource)
          .returns(())
        (callbacks.onRefundSucceeded _)
          .expects(where { (_, oldState, newState) =>
            oldState === refundingToSource &&
            newState.isInstanceOf[State.Failed]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(refundingToSource, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        // NOTE: Error logs are crucial; This test should verify error logs are actually written.
        LoggingTestKit
          .error("Refund failed due to an unexpected exception.")
          .withMessageContains("RefundingToSource")
          .withOccurrences(failureLimit)
          .expect {

            orchestrator ! RefundToSource

            inside(persistenceTestKit.expectNextPersistedType[RefundSucceeded](persistenceId.id)) { event =>
              expect(event.appRequestContext === appRequestContext)
              expect(event.accountNo === sourceAccountNo)
              expect(event.transactionId === refundTransactionId)
              expect(event.withdrawalTransactionId === withdrawalTransactionId)
              expect(event.amount === remittanceAmount)
            }
            inside(expectStateEventually[State.Failed](orchestrator)) { state =>
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

      "resume the refund automatically when it is recovered" in {

        val bankAccountApplication = mock[BankAccountApplication]
        (bankAccountApplication
          .refund(_: AccountNo, _: TransactionId, _: TransactionId, _: BigInt)(_: AppRequestContext))
          .expects(sourceAccountNo, refundTransactionId, withdrawalTransactionId, remittanceAmount, appRequestContext)
          .returns(Future.successful(RefundResult.Succeeded(100)))

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, refundingToSource)
          .onCall(Context.DefaultCallbacks.onRecoveryCompleted _) // Should delegate to the DefaultCallbacks.
        (callbacks.onRefundSucceeded _)
          .expects(where { (_, oldState, newState) =>
            oldState === refundingToSource &&
            newState.isInstanceOf[State.Failed]
          }).returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(refundingToSource, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        // Don't send RefundToSource to the orchestrator.
        // The orchestrator resumes the refund automatically.

        persistenceTestKit.expectNextPersistedType[RefundSucceeded](persistenceId.id)
        expectStateEventually[State.Failed](orchestrator)

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

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, succeeded)
          .returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(succeeded, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)
        val replyProbe   = testKit.createTestProbe[RemitReply]()

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        orchestrator ! Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)
        replyProbe.expectMessage(RemitSucceeded)
        persistenceTestKit.expectNothingPersisted(persistenceId.id)

      }

      "reply a InlaidArgument if the Remit command has a different sourceAccountNo" in {

        val bankAccountApplication = mock[BankAccountApplication]

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, succeeded)
          .returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(succeeded, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)
        val replyProbe   = testKit.createTestProbe[RemitReply]()

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val remitWithDifferentSourceAccountNo =
          Remit(AccountNo(UUID.randomUUID().toString), destinationAccountNo, remittanceAmount, replyProbe.ref)
        orchestrator ! remitWithDifferentSourceAccountNo
        replyProbe.expectMessage(InvalidArgument)
        persistenceTestKit.expectNothingPersisted(persistenceId.id)

      }

      "reply a InlaidArgument if the Remit command has a different destinationAccountNo" in {

        val bankAccountApplication = mock[BankAccountApplication]

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, succeeded)
          .returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(succeeded, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)
        val replyProbe   = testKit.createTestProbe[RemitReply]()

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val remitWithDifferentDestinationAccountNo =
          Remit(sourceAccountNo, AccountNo(UUID.randomUUID().toString), remittanceAmount, replyProbe.ref)
        orchestrator ! remitWithDifferentDestinationAccountNo
        replyProbe.expectMessage(InvalidArgument)
        persistenceTestKit.expectNothingPersisted(persistenceId.id)

      }

      "reply a InlaidArgument if the Remit command has a different remittance amount" in {

        val bankAccountApplication = mock[BankAccountApplication]

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, succeeded)
          .returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(succeeded, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)
        val replyProbe   = testKit.createTestProbe[RemitReply]()

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val remitWithDifferentRemittanceAmount =
          Remit(sourceAccountNo, destinationAccountNo, remittanceAmount + 1, replyProbe.ref)
        orchestrator ! remitWithDifferentRemittanceAmount
        replyProbe.expectMessage(InvalidArgument)
        persistenceTestKit.expectNothingPersisted(persistenceId.id)

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

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, failed)
          .returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(failed, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)
        val replyProbe   = testKit.createTestProbe[RemitReply]()

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        orchestrator ! Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)
        replyProbe.expectMessage(ShortBalance)
        persistenceTestKit.expectNothingPersisted(persistenceId.id)

      }

      "reply a ExcessBalance" in {

        val failed = failedWithFailureReply(ExcessBalance)

        val bankAccountApplication = mock[BankAccountApplication]

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, failed)
          .returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(failed, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)
        val replyProbe   = testKit.createTestProbe[RemitReply]()

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        orchestrator ! Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)
        replyProbe.expectMessage(ExcessBalance)
        persistenceTestKit.expectNothingPersisted(persistenceId.id)

      }

      "reply a InlaidArgument if the Remit command has a different sourceAccountNo" in {

        val failed = failedWithFailureReply(ShortBalance)

        val bankAccountApplication = mock[BankAccountApplication]

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, failed)
          .returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(failed, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)
        val replyProbe   = testKit.createTestProbe[RemitReply]()

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val remitWithDifferentSourceAccountNo =
          Remit(AccountNo(UUID.randomUUID().toString), destinationAccountNo, remittanceAmount, replyProbe.ref)
        orchestrator ! remitWithDifferentSourceAccountNo
        replyProbe.expectMessage(InvalidArgument)
        persistenceTestKit.expectNothingPersisted(persistenceId.id)

      }

      "reply a InlaidArgument if the Remit command has a different destinationAccountNo" in {

        val failed = failedWithFailureReply(ShortBalance)

        val bankAccountApplication = mock[BankAccountApplication]

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, failed)
          .returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(failed, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)
        val replyProbe   = testKit.createTestProbe[RemitReply]()

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val remitWithDifferentDestinationAccountNo =
          Remit(sourceAccountNo, AccountNo(UUID.randomUUID().toString), remittanceAmount, replyProbe.ref)
        orchestrator ! remitWithDifferentDestinationAccountNo
        replyProbe.expectMessage(InvalidArgument)
        persistenceTestKit.expectNothingPersisted(persistenceId.id)

      }

      "reply a InlaidArgument if the Remit command has a different remittance amount" in {

        val failed = failedWithFailureReply(ShortBalance)

        val bankAccountApplication = mock[BankAccountApplication]

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, failed)
          .returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(failed, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)
        val replyProbe   = testKit.createTestProbe[RemitReply]()

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val remitWithDifferentRemittanceAmount =
          Remit(sourceAccountNo, destinationAccountNo, remittanceAmount + 1, replyProbe.ref)
        orchestrator ! remitWithDifferentRemittanceAmount
        replyProbe.expectMessage(InvalidArgument)
        persistenceTestKit.expectNothingPersisted(persistenceId.id)

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

        val callbacks = mock[Context.Callbacks]
        (callbacks.onRecoveryCompleted _)
          .expects(*, earlyFailed)
          .returns(())

        val (persistenceId, orchestratorBehavior) =
          createBehavior(earlyFailed, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)
        val replyProbe   = testKit.createTestProbe[RemitReply]()

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val remit =
          Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)
        orchestrator ! remit
        replyProbe.expectMessage(InvalidArgument)
        persistenceTestKit.expectNothingPersisted(persistenceId.id)

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

        val (persistenceId, orchestratorBehavior) = createBehavior(State.Empty(tenant), bankAccountApplication)
        val orchestrator                          = spawn(orchestratorBehavior)

        val replyProbe = testKit.createTestProbe[RemitReply]()
        orchestrator ! Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)

        replyProbe.expectMessage(RemitSucceeded)
        persistenceTestKit.expectNextPersistedType[TransactionCreated](persistenceId.id)
        persistenceTestKit.expectNextPersistedType[WithdrawalSucceeded](persistenceId.id)
        persistenceTestKit.expectNextPersistedType[DepositSucceeded](persistenceId.id)
        expectStateEventually[State.Succeeded](orchestrator)

      }

      "reply a InvalidArgument if the source is the same as the destination" in {

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("account123")
        val destinationAccountNo                          = sourceAccountNo
        val remittanceAmount                              = BigInt(100)

        val bankAccountApplication = mock[BankAccountApplication]

        val (persistenceId, orchestratorBehavior) = createBehavior(State.Empty(tenant), bankAccountApplication)
        val orchestrator                          = spawn(orchestratorBehavior)

        val replyProbe = testKit.createTestProbe[RemitReply]()
        orchestrator ! Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)

        replyProbe.expectMessage(InvalidArgument)
        persistenceTestKit.expectNextPersistedType[InvalidRemittanceRequested](persistenceId.id)
        expectStateEventually[State.EarlyFailed](orchestrator)

      }

      "reply a InvalidArgument if the given remittance amount is negative" in {

        implicit val appRequestContext: AppRequestContext = generateAppRequestContext()
        val sourceAccountNo                               = AccountNo("source")
        val destinationAccountNo                          = AccountNo("destination")
        val remittanceAmount                              = BigInt(-1)

        val bankAccountApplication = mock[BankAccountApplication]

        val (persistenceId, orchestratorBehavior) = createBehavior(State.Empty(tenant), bankAccountApplication)
        val orchestrator                          = spawn(orchestratorBehavior)

        val replyProbe = testKit.createTestProbe[RemitReply]()
        orchestrator ! Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)

        replyProbe.expectMessage(InvalidArgument)
        persistenceTestKit.expectNextPersistedType[InvalidRemittanceRequested](persistenceId.id)
        expectStateEventually[State.EarlyFailed](orchestrator)

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

        val (persistenceId, orchestratorBehavior) = createBehavior(State.Empty(tenant), bankAccountApplication)
        val orchestrator                          = spawn(orchestratorBehavior)

        val replyProbe = testKit.createTestProbe[RemitReply]()
        orchestrator ! Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)

        replyProbe.expectMessage(ShortBalance)
        persistenceTestKit.expectNextPersistedType[TransactionCreated](persistenceId.id)
        persistenceTestKit.expectNextPersistedType[BalanceShorted](persistenceId.id)
        expectStateEventually[State.Failed](orchestrator)

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

        val (persistenceId, orchestratorBehavior) = createBehavior(State.Empty(tenant), bankAccountApplication)
        val orchestrator                          = spawn(orchestratorBehavior)

        val replyProbe = testKit.createTestProbe[RemitReply]()
        orchestrator ! Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)

        // replyProbe.expectMessage(ExcessBalance)
        persistenceTestKit.expectNextPersistedType[TransactionCreated](persistenceId.id)
        persistenceTestKit.expectNextPersistedType[WithdrawalSucceeded](persistenceId.id)
        persistenceTestKit.expectNextPersistedType[BalanceExceeded](persistenceId.id)
        persistenceTestKit.expectNextPersistedType[RefundSucceeded](persistenceId.id)
        expectStateEventually[State.Failed](orchestrator)

      }

      "resume at a WithdrawingFromSource, process commands, and then replies a RemitSucceeded" in {

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

        val callbacks = mock[Context.Callbacks]
        // Only onRecoveryCompleted, we should not delegate to the DefaultCallbacks.
        // We will manually emit a WithdrawalFromSource command in this test.
        (callbacks.onRecoveryCompleted _)
          .expects(*, withdrawingFromSource)
          .returns(())
        (callbacks.onWithdrawSucceeded _)
          .expects(*, withdrawingFromSource, *)
          .onCall(Context.DefaultCallbacks.onWithdrawSucceeded _)
        (callbacks.onDepositSucceeded _)
          .expects(*, *, *)
          .onCall(Context.DefaultCallbacks.onDepositSucceeded _)

        val (persistenceId, orchestratorBehavior) =
          createBehavior(withdrawingFromSource, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        // We emulate the orchestrator receiving a Remit command in a WithdrawingFromSource by emitting the command manually.
        // And then, we emulate the orchestrator recovery by emitting a WithdrawFromSource command.
        val replyProbe = testKit.createTestProbe[RemitReply]()
        orchestrator ! Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)
        orchestrator ! WithdrawFromSource

        replyProbe.expectMessage(RemitSucceeded)
        persistenceTestKit.expectNextPersistedType[WithdrawalSucceeded](persistenceId.id)
        persistenceTestKit.expectNextPersistedType[DepositSucceeded](persistenceId.id)
        expectStateEventually[State.Succeeded](orchestrator)

      }

      "resume at a DepositingToDestination, process commands, and then replies a RemitSucceeded" in {

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

        val callbacks = mock[Context.Callbacks]
        // Only onRecoveryCompleted, we should not delegate to the DefaultCallbacks.
        // We will manually emit a DepositToDestination command in this test.
        (callbacks.onRecoveryCompleted _)
          .expects(*, depositingToDestination)
          .returns(())
        (callbacks.onDepositSucceeded _)
          .expects(*, *, *)
          .onCall(Context.DefaultCallbacks.onDepositSucceeded _)

        val (persistenceId, orchestratorBehavior) =
          createBehavior(depositingToDestination, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        // We emulate the orchestrator receiving a Remit command in a DepositingToDestination by emitting the command manually.
        // And then, we emulate the orchestrator recovery by emitting a DepositToDestination command.
        val replyProbe = testKit.createTestProbe[RemitReply]()
        orchestrator ! Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)
        orchestrator ! DepositToDestination

        replyProbe.expectMessage(RemitSucceeded)
        persistenceTestKit.expectNextPersistedType[DepositSucceeded](persistenceId.id)
        expectStateEventually[State.Succeeded](orchestrator)

      }

      "resume at a RefundingToSource, process commands, and then replies a ExcessBalance" in {

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

        val callbacks = mock[Context.Callbacks]
        // Only onRecoveryCompleted, we should not delegate to the DefaultCallbacks.
        // We will manually emit a RefundToSource command in this test.
        (callbacks.onRecoveryCompleted _)
          .expects(*, refundingToSource)
          .returns(())
        (callbacks.onRefundSucceeded _)
          .expects(*, *, *)
          .onCall(Context.DefaultCallbacks.onRefundSucceeded _)

        val (persistenceId, orchestratorBehavior) =
          createBehavior(refundingToSource, bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        // We emulate the orchestrator receiving a Remit command in a RefundingToSource by emitting the command manually.
        // And then, we emulate the orchestrator recovery by emitting a RefundToSource command.
        val replyProbe = testKit.createTestProbe[RemitReply]()
        orchestrator ! Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)
        orchestrator ! RefundToSource

        replyProbe.expectMessage(ExcessBalance)
        persistenceTestKit.expectNextPersistedType[RefundSucceeded](persistenceId.id)
        expectStateEventually[State.Failed](orchestrator)

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

        val callbacks = mock[Context.Callbacks]
        // onRecoveryCompleted will be called twice
        // since the orchestrator restarts due to a journal failure.
        (callbacks.onRecoveryCompleted _)
          .expects(*, *)
          .twice()
          .onCall(Context.DefaultCallbacks.onRecoveryCompleted _)
        (callbacks.onTransactionCreated _)
          .expects(*, *, *, *)
          .onCall { (context, oldState, newState, remitCommand) =>
            // Once the orchestrator persist a transaction,
            // the orchestrator will resume its behavior even if a journal failure occurs.
            // This test emulate that the first WithdrawalSucceeded persisting will fail and then the second one will succeed.
            persistenceTestKit.failNextPersisted()
            Context.DefaultCallbacks.onTransactionCreated(context, oldState, newState, remitCommand)
          }
        (callbacks.onWithdrawSucceeded _)
          .expects(*, *, *)
          .onCall(Context.DefaultCallbacks.onWithdrawSucceeded _)
        (callbacks.onDepositSucceeded _)
          .expects(*, *, *)
          .onCall(Context.DefaultCallbacks.onDepositSucceeded _)

        val (persistenceId, orchestratorBehavior) =
          createBehavior(State.Empty(tenant), bankAccountApplication, callbacks = callbacks)
        val orchestrator = spawn(orchestratorBehavior)

        val replyProbe = testKit.createTestProbe[RemitReply]()
        orchestrator ! Remit(sourceAccountNo, destinationAccountNo, remittanceAmount, replyProbe.ref)

        replyProbe.expectMessage(RemitSucceeded)
        persistenceTestKit.expectNextPersistedType[TransactionCreated](persistenceId.id)
        persistenceTestKit.expectNextPersistedType[WithdrawalSucceeded](persistenceId.id)
        persistenceTestKit.expectNextPersistedType[DepositSucceeded](persistenceId.id)
        expectStateEventually[State.Succeeded](orchestrator)

      }

    }

  }

}
