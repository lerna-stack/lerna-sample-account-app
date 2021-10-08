package myapp.application.account

import akka.actor.NoSerializationVerificationNeeded
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, TimerScheduler }
import akka.actor.typed.{ ActorRef, Behavior, SupervisorStrategy }
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity, EntityTypeKey }
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import akka.persistence.typed.{ PersistenceId, RecoveryCompleted }
import com.fasterxml.jackson.annotation.{ JsonSubTypes, JsonTypeInfo }
import lerna.log.{ AppLogger, AppTypedActorLogging }
import lerna.util.lang.Equals._
import lerna.util.trace.TraceId
import myapp.adapter.account.BankAccountApplication.{ DepositResult, RefundResult, WithdrawalResult }
import myapp.adapter.account.{ AccountNo, BankAccountApplication, TransactionId }
import myapp.utility.AppRequestContext
import myapp.utility.tenant.AppTenant

import java.util.UUID
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Success }

/** 送金アクター(送金オーケストレータ)
  *
  * ==概要==
  * 入金、出金、返金を提供する [[BankAccountApplication]] を用いて送金を実現する。
  *
  * 送金は Saga & オーケストレーション 方式でトランザクション制御を行う。
  *
  * 送金オーケストレータは送金ごとに作成される。
  * 送金オーケストレータはAkka Cluster Sharding における Entity として稼働する。
  * 送金オーケストレータは送金が完了した(成功もしくは失敗した)場合にのみ停止する。
  * Akka Cluster Sharding の設定は [[RemittanceApplicationImpl]] を参照すること。
  *
  * ==リクエスト&レスポンス方法==
  * 送金オーケストレータは、送金完了時(成功失敗問わず)にレスポンスを返す。
  * 送金が完了するまでの間、受信した 送金コマンド [[RemittanceOrchestratorBehavior.Remit]] は内部バッファに stash される。
  *
  * Akka Actor の [[https://doc.akka.io/docs/akka/current/typed/stash.html#stash Stash]] ではなく、
  * Akka Persistence の [[https://doc.akka.io/docs/akka/current/typed/persistence.html#stash Stash]] が使用されることに注意すること。
  * Akka Persistence の Stash を使用することで Journal Failure 等が発生したときに、stash されているコマンドのドロップを防止できる。
  *
  * Akka Persistence の Stash Buffer は、 EventSourcedBehavior の内部動作用(internal)と `Effect.stash`用(user)で分離されており、
  * `Effect.stash` で Stash Buffer がオーバーフローする場合でも EventSourcedBehavior の内部動作には影響を与えない。
  * `Effect.stash` で Stash Buffer がオーバーフローする場合、追加しようとしているコマンドは破棄される。
  * Stash Buffer (user用) のサイズは、`akka.persistence.typed.stash-capacity` で設定できる。
  *
  * ※ もしも [[RemittanceOrchestratorBehavior.Remit]] 以外のコマンドを `Effect.stash` しようとする場合には注意が必要である。
  * 新しく stash しようとしているコマンドがドロップされても問題ないかを検討する方が良い。
  *
  * ==エラー処理==
  * 送金オーケストレータでは多数の失敗を処理する必要がある。
  * 基本的な戦略は次のとおりである。
  *
  *  - タイムアウト
  *    - APIが冪等であるためリトライする。
  *    - タイムアウトが発生した場合、API呼び出しの成否を判断できないため、必ずリトライしなくてはならない。
  *  - 予期しない失敗
  *    - 例外発生などの予期しない例外の場合はリトライする。
  *    - タイムアウトと同様に、API呼び出しの成否を判断できないため、必ずリトライしなくてはならない。
  *    - 何らかの人手による復旧が必要になっている可能性があり、自動復旧しないかもしれない。
  *  - 出金失敗(タイムアウト以外)
  *    - 残高不足などの理由で出金できない場合は、即座に送金を失敗にする。
  *  - 入金失敗(タイムアウト以外)
  *    - 残高超過などの理由で入金できない場合は、返金を実施する。
  *  - 返金失敗
  *    - 返金は必ず実施しなければならないため、常にリトライする。
  *
  * @see [[https://docs.microsoft.com/ja-jp/azure/architecture/reference-architectures/saga/saga saga 分散トランザクション - Azure Design Patterns | Microsoft Docs]]
  * @see [[https://github.com/akka/akka/blob/v2.6.12/akka-persistence-typed/src/main/scala/akka/persistence/typed/internal/StashManagement.scala#L30-L42 StashManagement.scala]]
  * @see [[https://github.com/akka/akka/blob/v2.6.12/akka-persistence-typed/src/main/scala/akka/persistence/typed/internal/Running.scala Running.scala]]
  */
object RemittanceOrchestratorBehavior extends AppTypedActorLogging {

  /** [[RemittanceOrchestratorBehavior]] の設定
    *
    * @param journalPluginId イベント永続化PluginId
    * @param snapshotPluginId スナップショット永続化PluginId
    * @param withdrawalRetryDelay 出金をリトライする際の遅延時間
    * @param depositRetryDelay 入金をリトライする際の遅延時間
    * @param refundRetryDelay 返金をリトライする際の遅延時間
    * @param passivateTimeout 一定時間コマンドが発生していない場合に Passivate する
    *                         Passivate は 送金取引が完了している場合にのみ発生し、送金取引中には発生しない。
    * @param persistenceFailureRestartMinBackOff 永続化失敗時リスタートの MinBackOff
    * @param persistenceFailureRestartMaxBackOff 永続化失敗時リスタートの MaxBackOff
    * @param persistenceFailureRestartRandomFactor 永続化失敗時リスタートの RandomFactor
    */
  final case class Settings(
      journalPluginId: String,
      snapshotPluginId: String,
      withdrawalRetryDelay: FiniteDuration,
      depositRetryDelay: FiniteDuration,
      refundRetryDelay: FiniteDuration,
      passivateTimeout: FiniteDuration,
      persistenceFailureRestartMinBackOff: FiniteDuration,
      persistenceFailureRestartMaxBackOff: FiniteDuration,
      persistenceFailureRestartRandomFactor: Double,
  )

  final case class EntityId(value: String) extends AnyVal

  def typeKey(tenant: AppTenant): EntityTypeKey[Command] =
    EntityTypeKey(s"RemittanceOrchestrator-${tenant.id}")

  def entity(
      tenant: AppTenant,
      settings: Settings,
      bankAccountApplication: BankAccountApplication,
  ): Entity[Command, ShardingEnvelope[Command]] = {
    val initialState         = State.Empty(tenant)
    val transactionIdFactory = Context.DefaultTransactionIdFactory
    val contextFactory       = Context.DefaultCallbacks
    Entity(typeKey(tenant))(context => {
      val entityId      = EntityId(context.entityId)
      val persistenceId = PersistenceId(context.entityTypeKey.name, entityId.value)
      RemittanceOrchestratorBehavior(
        entityId,
        persistenceId,
        settings,
        bankAccountApplication,
        context.shard,
        initialState,
        transactionIdFactory,
        contextFactory,
      )
    }).withStopMessage(Stop)
  }

  // This method is public since it is helpful for unit testing.
  def apply(
      entityId: EntityId,
      persistenceId: PersistenceId,
      settings: Settings,
      bankAccountApplication: BankAccountApplication,
      shard: ActorRef[ClusterSharding.ShardCommand],
      initialState: State,
      transactionIdFactory: Context.TransactionIdFactory,
      callbacks: Context.Callbacks,
  ): Behavior[Command] = withLogger { logger =>
    Behaviors.setup { actorContext =>
      actorContext.setLoggerName(RemittanceOrchestratorBehavior.getClass)
      Behaviors.withTimers { timers =>
        val orchestratorContext =
          Context(
            settings,
            bankAccountApplication,
            shard,
            actorContext,
            timers,
            logger,
            transactionIdFactory,
            callbacks,
          )
        EventSourcedBehavior[Command, DomainEvent, State](
          persistenceId,
          emptyState = initialState,
          commandHandler = (state, command) => state.applyCommand(orchestratorContext, command),
          eventHandler = (state, event) => state.applyEvent(orchestratorContext, event),
        )
          .receiveSignal({
            case (state, RecoveryCompleted) =>
              // Since we have to resume this transaction (e.g. withdrawal, deposit, refund),
              // we will trigger some tasks when this actor recovers.
              state.recoveryCompleted(orchestratorContext)
          })
          .onPersistFailure(
            // This might be helpful even if we use Akka Cluster Sharding Remember Entities
            // since stashed commands are preserved when persistence failure occurs.
            // See:
            //  - https://doc.akka.io/docs/akka/current/typed/persistence.html#journal-failures
            //  - https://doc.akka.io/docs/akka/current/typed/persistence.html#journal-failures
            SupervisorStrategy.restartWithBackoff(
              settings.persistenceFailureRestartMinBackOff,
              settings.persistenceFailureRestartMaxBackOff,
              settings.persistenceFailureRestartRandomFactor,
            ),
          )
          .withJournalPluginId(settings.journalPluginId)
          .withSnapshotPluginId(settings.snapshotPluginId)
      }
    }
  }

  sealed trait Command

  /** コマンド: 送金
    *
    * @param sourceAccountNo 送金元口座番号
    * @param destinationAccountNo 送金先口座番号
    * @param amount 送金金額
    * @param replyTo 返信先
    */
  final case class Remit(
      sourceAccountNo: AccountNo,
      destinationAccountNo: AccountNo,
      amount: BigInt,
      replyTo: ActorRef[RemitReply],
  )(implicit
      val appRequestContext: AppRequestContext,
  ) extends Command

  /** コマンド:停止
    *
    * このアクターを停止する。
    * このメッセージはパッシベーションやリバランスでのアクター停止で使用される。
    *
    * @see [[https://doc.akka.io/docs/akka/2.6.12/typed/cluster-sharding.html#passivation Passivation]]
    */
  case object Stop extends Command

  // 内部コマンド
  // テストで使用するために公開している。
  //
  // ここより下は アクターが自身に対して発行するコマンドである。
  // アクター外から使用することは想定しておらず、そのような使用を場合の動作は保証されない。
  //

  /** 送金元口座から出金する */
  case object WithdrawFromSource extends Command

  /** 送金元口座からの出金が完了した */
  final case class WithdrawCompleted(result: WithdrawalResult) extends Command

  /** 送金元口座からの出金が予期しない例外で失敗した */
  final case class WithdrawFailedWithUnexpectedException(cause: Throwable) extends Command

  /** 送金先口座へ入金する */
  case object DepositToDestination extends Command

  /** 送金先口座への入金が完了した */
  final case class DepositCompleted(result: DepositResult) extends Command

  /** 送金先口座への入金が予期しない例外で失敗した */
  final case class DepositFailedWithUnexpectedException(cause: Throwable) extends Command

  /** 送金元口座へ返金する */
  case object RefundToSource extends Command

  /** 送金元口座への返金が完了した */
  final case class RefundCompleted(result: RefundResult) extends Command

  /** 送金元口座への返金が予期しない例外で失敗した */
  final case class RefundFailedWithUnexpectedException(cause: Throwable) extends Command

  /** 送金を完了する */
  case object CompleteTransaction extends Command

  /** コマンド:パッシベーション
    *
    * このアクターのパッシベーションを開始する。
    */
  case object Passivate extends Command

  /** コマンド:状態取得
    *
    * アクターの現在の状態を取得する。
    * このコマンドはテストでのみ使用することを意図しており、テスト以外で使用しないこと。
    * このコマンドはノード間で送信しないため、Serialize できる必要はない。
    */
  final case class InspectState(replyTo: ActorRef[InspectStateReply])
      extends Command
      with NoSerializationVerificationNeeded

  sealed trait Reply

  /** 送金結果 */
  sealed trait RemitReply     extends Reply
  case object RemitSucceeded  extends RemitReply
  sealed trait RemitFailed    extends RemitReply
  case object InvalidArgument extends RemitFailed
  case object ShortBalance    extends RemitFailed
  case object ExcessBalance   extends RemitFailed

  /** アクターの状態
    *
    * この返信はテストでのみ使用することを意図しており、テスト以外では使用しないこと。
    * このコマンドはノード間で送信しないため、Serialize できる必要はない。
    */
  final case class InspectStateReply(state: State) extends Reply with NoSerializationVerificationNeeded

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
  @JsonSubTypes(
    Array(
      new JsonSubTypes.Type(name = "TransactionCreated", value = classOf[TransactionCreated]),
      new JsonSubTypes.Type(name = "WithdrawalSucceeded", value = classOf[WithdrawalSucceeded]),
      new JsonSubTypes.Type(name = "DepositSucceeded", value = classOf[DepositSucceeded]),
      new JsonSubTypes.Type(name = "InvalidRemittanceRequested", value = classOf[InvalidRemittanceRequested]),
      new JsonSubTypes.Type(name = "BalanceShorted", value = classOf[BalanceShorted]),
      new JsonSubTypes.Type(name = "BalanceExceeded", value = classOf[BalanceExceeded]),
    ),
  )
  sealed trait DomainEvent {
    def appRequestContext: AppRequestContext
  }

  /** 送金取引(トランザクション) を作成した */
  final case class TransactionCreated(
      sourceAccountNo: AccountNo,
      destinationAccountNo: AccountNo,
      amount: BigInt,
      withdrawalTransactionId: TransactionId,
      depositTransactionId: TransactionId,
      refundTransactionId: TransactionId,
  )(implicit
      val appRequestContext: AppRequestContext,
  ) extends DomainEvent

  /** 出金に成功した */
  final case class WithdrawalSucceeded(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: BigInt,
  )(implicit
      val appRequestContext: AppRequestContext,
  ) extends DomainEvent

  /** 入金に成功した */
  final case class DepositSucceeded(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: BigInt,
  )(implicit
      val appRequestContext: AppRequestContext,
  ) extends DomainEvent

  /** 返金に成功した */
  final case class RefundSucceeded(
      accountNo: AccountNo,
      transactionId: TransactionId,
      withdrawalTransactionId: TransactionId,
      amount: BigInt,
  )(implicit
      val appRequestContext: AppRequestContext,
  ) extends DomainEvent

  /** 送金失敗:不正な送金を要求された */
  final case class InvalidRemittanceRequested(
      sourceAccountNo: AccountNo,
      destinationAccountNo: AccountNo,
      amount: BigInt,
  )(implicit
      val appRequestContext: AppRequestContext,
  ) extends DomainEvent

  /** 送金失敗:残高不足 */
  final case class BalanceShorted(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: BigInt,
  )(implicit
      val appRequestContext: AppRequestContext,
  ) extends DomainEvent

  /** 送金失敗:残高超過 */
  final case class BalanceExceeded(
      accountNo: AccountNo,
      transactionId: TransactionId,
      amount: BigInt,
  )(implicit
      val appRequestContext: AppRequestContext,
  ) extends DomainEvent

  final case class Context(
      settings: Settings,
      bankAccountApplication: BankAccountApplication,
      shard: ActorRef[ClusterSharding.ShardCommand],
      actorContext: ActorContext[Command],
      timers: TimerScheduler[Command],
      logger: AppLogger,
      transactionIdFactory: Context.TransactionIdFactory,
      callbacks: Context.Callbacks,
  ) {
    def logEvent(newState: State, oldState: State, event: DomainEvent)(implicit ctx: AppRequestContext): Unit = {
      logInfo(newState, s"oldState=${oldState.toString} event=${event.toString}")
    }
    def logInfo(state: State, message: String)(implicit ctx: AppRequestContext): Unit = {
      val stateName = state.getClass.getName
      logger.info("[{}] {}", stateName, message)
    }
    def logWarn(state: State, message: String)(implicit ctx: AppRequestContext): Unit = {
      val stateName = state.getClass.getName
      logger.warn("[{}] {}", stateName, message)
    }
    def logError(state: State, cause: Throwable, message: String)(implicit ctx: AppRequestContext): Unit = {
      val stateName = state.getClass.getName
      logger.error(cause, "[{}] {}", stateName, message)
    }
    def logError(state: State, message: String)(implicit ctx: AppRequestContext): Unit = {
      val stateName = state.getClass.getName
      logger.error("[{}] {}", stateName, message)
    }
  }

  /** Defines classes,traits,objects for unit testing.
    *
    * Use default instances except for testing.
    */
  object Context {

    /** Generates [[TransactionId]]
      *
      *  We have to generate unique transaction IDs.
      *  Generated transaction IDs are used by
      *   - withdrawal
      *   - deposit
      *   - refund
      *
      * Be careful that [[BankAccountApplication]] will not distinguish the purpose of transaction IDs.
      * We have to generate IDs uniquely regardless of their purpose.
      */
    trait TransactionIdFactory {
      def generate(): TransactionId
    }
    object DefaultTransactionIdFactory extends TransactionIdFactory {
      override def generate(): TransactionId = {
        // UUID v4 is a probabilistic strategy for generating IDs.
        // We can replace this strategy with another one we prefer.
        TransactionId(UUID.randomUUID().toString)
      }
    }

    /** Callbacks
      *
      * The callbacks are made for easy unit testing.
      * It is not intended to extend behaviors.
      * Keep in mind that we have to keep the implementation simple as possible.
      */
    trait Callbacks {
      def onRecoveryCompleted(context: Context, state: State): Unit
      def onTransactionCreated(context: Context, oldState: State.Empty, newState: State, remitCommand: Remit): Unit
      def onInvalidRemitRequested(context: Context, oldState: State.Empty, newState: State, remitCommand: Remit): Unit
      def onWithdrawSucceeded(context: Context, oldState: State.WithdrawingFromSource, newState: State): Unit
      def onWithdrawFailed(context: Context, oldState: State.WithdrawingFromSource, newState: State): Unit
      def onDepositSucceeded(context: Context, oldState: State.DepositingToDestination, newState: State): Unit
      def onDepositFailed(context: Context, oldState: State.DepositingToDestination, newState: State): Unit
      def onRefundSucceeded(context: Context, oldState: State.RefundingToSource, newState: State): Unit
    }
    object DefaultCallbacks extends Callbacks {
      override def onRecoveryCompleted(context: Context, state: State): Unit = {
        state match {
          case _: State.Empty =>
          // Do nothing
          case _: State.WithdrawingFromSource =>
            context.actorContext.self ! WithdrawFromSource
          case _: State.DepositingToDestination =>
            context.actorContext.self ! DepositToDestination
          case _: State.RefundingToSource =>
            context.actorContext.self ! RefundToSource
          case _: State.Succeeded | _: State.Failed | _: State.EarlyFailed =>
            val passivateTimeout = context.settings.passivateTimeout
            context.actorContext.setReceiveTimeout(passivateTimeout, Passivate)
        }
      }
      override def onTransactionCreated(
          context: Context,
          oldState: State.Empty,
          newState: State,
          remitCommand: Remit,
      ): Unit = {
        // The Remit command will be stashed, and then a withdrawal will be in progress.
        //
        // The sending order might be crucial.
        // Stashed commands are preserved and processed later in case of a failure while storing events.
        // Be careful that we should use onPersistentFailure with a backoff supervisor strategy.
        // See https://doc.akka.io/docs/akka/current/typed/persistence.html#stash
        //
        context.actorContext.self ! remitCommand
        context.actorContext.self ! WithdrawFromSource
      }
      override def onInvalidRemitRequested(
          context: Context,
          oldState: State.Empty,
          newState: State,
          remitCommand: Remit,
      ): Unit = {
        // The Remit command should be processed after this callback.
        context.actorContext.self ! remitCommand
        // Though Sending a CompleteTransaction might not be needed here,
        // it is great for ensuring that all stashed commands will be un-stashed by sending the command.
        context.actorContext.self ! CompleteTransaction
      }
      override def onWithdrawSucceeded(
          context: Context,
          oldState: State.WithdrawingFromSource,
          newState: State,
      ): Unit = {
        context.actorContext.self ! DepositToDestination
      }
      override def onWithdrawFailed(context: Context, oldState: State.WithdrawingFromSource, newState: State): Unit = {
        context.actorContext.self ! CompleteTransaction
      }
      override def onDepositSucceeded(
          context: Context,
          oldState: State.DepositingToDestination,
          newState: State,
      ): Unit = {
        context.actorContext.self ! CompleteTransaction
      }
      override def onDepositFailed(context: Context, oldState: State.DepositingToDestination, newState: State): Unit = {
        context.actorContext.self ! RefundToSource
      }
      override def onRefundSucceeded(context: Context, oldState: State.RefundingToSource, newState: State): Unit = {
        context.actorContext.self ! CompleteTransaction
      }
    }
  }

  /** TimerKeys used for retries */
  private object TimerKeys {
    case object RetryWithdraw
    case object RetryDeposit
    case object RetryRefund
  }

  type Effect = akka.persistence.typed.scaladsl.Effect[DomainEvent, State]

  sealed trait State {

    def applyCommand(context: Context, command: Command): Effect

    def applyEvent(context: Context, event: DomainEvent): State

    def recoveryCompleted(context: Context): Unit = {
      context.callbacks.onRecoveryCompleted(context, this)
    }

    def ignoreUnexpectedCommand(context: Context, command: Command)(implicit ctx: AppRequestContext): Effect = {
      Effect.unhandled.thenRun { _ =>
        val message = s"Unexpected command ${command.toString}"
        context.logWarn(this, message)
      }
    }

    def throwIllegalStateException(context: Context, event: DomainEvent)(implicit ctx: AppRequestContext): Nothing = {
      val message = s"Unexpected event ${event.toString}"
      context.logError(this, message)
      throw new IllegalStateException(message)
    }

    def applyInspectState(command: InspectState): Effect = {
      Effect.none.thenReply(command.replyTo)(InspectStateReply)
    }

  }

  object State {

    final case class Empty(tenant: AppTenant) extends State {

      override def applyCommand(context: Context, command: Command): Effect = {
        command match {
          case command: Remit =>
            applyRemit(context, command)
          case Stop =>
            Effect.stop()
          case command: InspectState =>
            applyInspectState(command)
          case _ =>
            // Make and use an unknown context since this state is not bounded to any request.
            implicit val unknown: AppRequestContext = AppRequestContext(TraceId.unknown, tenant)
            ignoreUnexpectedCommand(context, command)
        }
      }

      private def applyRemit(context: Context, command: Remit): Effect = {
        implicit val ctx: AppRequestContext                         = command.appRequestContext
        val Remit(sourceAccountNo, destinationAccountNo, amount, _) = command
        val withdrawalTransactionId                                 = context.transactionIdFactory.generate()
        val depositTransactionId                                    = context.transactionIdFactory.generate()
        val refundTransactionId                                     = context.transactionIdFactory.generate()
        val isValidRemitCommand = {
          (sourceAccountNo !== destinationAccountNo) && amount > 0
        }
        if (isValidRemitCommand) {
          val transactionCreated = TransactionCreated(
            sourceAccountNo,
            destinationAccountNo,
            amount,
            withdrawalTransactionId,
            depositTransactionId,
            refundTransactionId,
          )(ctx)
          Effect
            .persist(transactionCreated)
            .thenRun { newState: State =>
              context.logEvent(newState, this, transactionCreated)(ctx)
              context.callbacks.onTransactionCreated(context, this, newState, command)
            }
        } else {
          val failed = InvalidRemittanceRequested(
            sourceAccountNo,
            destinationAccountNo,
            amount,
          )(ctx)
          Effect
            .persist(failed)
            .thenRun { newState =>
              context.logEvent(newState, this, failed)(ctx)
              context.callbacks.onInvalidRemitRequested(context, this, newState, command)
            }
        }
      }

      override def applyEvent(context: Context, event: DomainEvent): State = {
        event match {
          case created @ TransactionCreated(
                sourceAccountNo,
                destinationAccountNo,
                amount,
                withdrawalTransactionId,
                depositTransactionId,
                refundTransactionId,
              ) =>
            WithdrawingFromSource(
              sourceAccountNo,
              destinationAccountNo,
              amount,
              withdrawalTransactionId,
              depositTransactionId,
              refundTransactionId,
            )(created.appRequestContext)
          case invalidRemittanceRequested @ InvalidRemittanceRequested(sourceAccountNo, destinationAccountNo, amount) =>
            EarlyFailed(
              sourceAccountNo,
              destinationAccountNo,
              amount,
            )(invalidRemittanceRequested.appRequestContext)
          case _ =>
            // Make and use an unknown context since this state is not bounded to any request.
            implicit val unknown: AppRequestContext = AppRequestContext(TraceId.unknown, tenant)
            throwIllegalStateException(context, event)
        }
      }

    }

    sealed trait TransactionState extends State {
      def appRequestContext: AppRequestContext
      def sourceAccountNo: AccountNo
      def destinationAccountNo: AccountNo
      def remittanceAmount: BigInt
      def isValidCommand(remitCommand: Remit): Boolean = {
        remitCommand.sourceAccountNo === sourceAccountNo &&
        remitCommand.destinationAccountNo === destinationAccountNo &&
        remitCommand.amount === remittanceAmount
      }
    }

    final case class WithdrawingFromSource(
        sourceAccountNo: AccountNo,
        destinationAccountNo: AccountNo,
        remittanceAmount: BigInt,
        withdrawalTransactionId: TransactionId,
        depositTransactionId: TransactionId,
        refundTransactionId: TransactionId,
    )(implicit
        val appRequestContext: AppRequestContext,
    ) extends TransactionState {

      override def applyCommand(context: Context, command: Command): Effect = {
        command match {
          case WithdrawFromSource =>
            applyWithdrawFromSource(context)
          case command: WithdrawCompleted =>
            applyWithdrawCompleted(context, command)
          case command: WithdrawFailedWithUnexpectedException =>
            applyWithdrawFailedWithUnexpectedException(context, command)
          case _: Remit =>
            Effect.stash()
          case Stop =>
            Effect.stop()
          case command: InspectState =>
            applyInspectState(command)
          case _ =>
            ignoreUnexpectedCommand(context, command)
        }
      }

      private def applyWithdrawFromSource(context: Context): Effect = {
        Effect.none.thenRun { _ =>
          val withdrawal =
            context.bankAccountApplication.withdraw(sourceAccountNo, withdrawalTransactionId, remittanceAmount)
          context.actorContext.pipeToSelf(withdrawal) {
            case Success(result) =>
              WithdrawCompleted(result)
            case Failure(cause) =>
              WithdrawFailedWithUnexpectedException(cause)
          }
        }
      }

      private def applyWithdrawCompleted(context: Context, command: WithdrawCompleted): Effect = {
        val WithdrawCompleted(result) = command
        result match {
          case WithdrawalResult.Succeeded(_) =>
            val withdrawalSucceeded =
              WithdrawalSucceeded(sourceAccountNo, withdrawalTransactionId, remittanceAmount)
            Effect
              .persist(withdrawalSucceeded)
              .thenRun { newState =>
                context.logEvent(newState, this, withdrawalSucceeded)
                context.callbacks.onWithdrawSucceeded(context, this, newState)
              }
          case WithdrawalResult.ShortBalance =>
            val balanceShorted = BalanceShorted(
              sourceAccountNo,
              withdrawalTransactionId,
              remittanceAmount,
            )
            Effect
              .persist(balanceShorted)
              .thenRun { newState =>
                context.logEvent(newState, this, balanceShorted)
                context.callbacks.onWithdrawFailed(context, this, newState)
              }
          case WithdrawalResult.Timeout =>
            Effect.none.thenRun { state: State =>
              val delay = context.settings.withdrawalRetryDelay
              context.logWarn(
                state,
                s"Withdrawal failed due to a timeout. This will retry withdrawal again after ${delay.toString}",
              )
              context.timers.startSingleTimer(TimerKeys.RetryWithdraw, WithdrawFromSource, delay)
            }
        }
      }

      private def applyWithdrawFailedWithUnexpectedException(
          context: Context,
          command: WithdrawFailedWithUnexpectedException,
      ): Effect = {
        val WithdrawFailedWithUnexpectedException(cause) = command
        Effect.none
          .thenRun { state: State =>
            val delay = context.settings.withdrawalRetryDelay
            val message =
              s"""
                 |Withdrawal failed with an unexpected exception.
                 |This failure might occur for the withdraw function's bug or breaking change.
                 |Though we might have to recover this failure by human operations, this will retry withdrawal again after ${delay.toString}.
                 |""".stripMargin
            context.logError(state, cause, message)
            context.timers.startSingleTimer(TimerKeys.RetryWithdraw, WithdrawFromSource, delay)
          }
      }

      override def applyEvent(context: Context, event: DomainEvent): State = {
        event match {
          case _: WithdrawalSucceeded =>
            DepositingToDestination(
              sourceAccountNo,
              destinationAccountNo,
              remittanceAmount,
              withdrawalTransactionId,
              depositTransactionId,
              refundTransactionId,
            )
          case _: BalanceShorted =>
            Failed(
              sourceAccountNo,
              destinationAccountNo,
              remittanceAmount,
              withdrawalTransactionId,
              depositTransactionId,
              refundTransactionId,
              ShortBalance,
            )
          case _ =>
            throwIllegalStateException(context, event)
        }
      }

    }

    final case class DepositingToDestination(
        sourceAccountNo: AccountNo,
        destinationAccountNo: AccountNo,
        remittanceAmount: BigInt,
        withdrawalTransactionId: TransactionId,
        depositTransactionId: TransactionId,
        refundTransactionId: TransactionId,
    )(implicit
        val appRequestContext: AppRequestContext,
    ) extends TransactionState {

      override def applyCommand(context: Context, command: Command): Effect = {
        command match {
          case DepositToDestination =>
            applyDepositToDestination(context)
          case command: DepositCompleted =>
            applyDepositCompleted(context, command)
          case command: DepositFailedWithUnexpectedException =>
            applyDepositFailedWithUnexpectedException(context, command)
          case _: Remit =>
            Effect.stash()
          case Stop =>
            Effect.stop()
          case command: InspectState =>
            applyInspectState(command)
          case _ =>
            ignoreUnexpectedCommand(context, command)
        }
      }

      private def applyDepositToDestination(context: Context): Effect = {
        Effect.none
          .thenRun { _ =>
            val deposit =
              context.bankAccountApplication.deposit(destinationAccountNo, depositTransactionId, remittanceAmount)
            context.actorContext.pipeToSelf(deposit) {
              case Success(result) =>
                DepositCompleted(result)
              case Failure(cause) =>
                DepositFailedWithUnexpectedException(cause)
            }
          }
      }

      private def applyDepositCompleted(context: Context, command: DepositCompleted): Effect = {
        val DepositCompleted(result) = command
        result match {
          case DepositResult.Succeeded(_) =>
            val depositSucceeded =
              DepositSucceeded(destinationAccountNo, depositTransactionId, remittanceAmount)
            Effect
              .persist(depositSucceeded)
              .thenRun { newState =>
                context.logEvent(newState, this, depositSucceeded)
                context.callbacks.onDepositSucceeded(context, this, newState)
              }
          case DepositResult.ExcessBalance =>
            val balanceExceeded = BalanceExceeded(
              destinationAccountNo,
              depositTransactionId,
              remittanceAmount,
            )
            Effect
              .persist(balanceExceeded)
              .thenRun { newState =>
                context.logEvent(newState, this, balanceExceeded)
                context.callbacks.onDepositFailed(context, this, newState)
              }
          case DepositResult.Timeout =>
            Effect.none.thenRun { state: State =>
              val delay = context.settings.depositRetryDelay
              context.logWarn(
                state,
                s"Deposit failed due to a timeout. This will retry deposit again after ${delay.toString}.",
              )
              context.timers.startSingleTimer(TimerKeys.RetryDeposit, DepositToDestination, delay)
            }
        }
      }

      private def applyDepositFailedWithUnexpectedException(
          context: Context,
          command: DepositFailedWithUnexpectedException,
      ): Effect = {
        val DepositFailedWithUnexpectedException(cause) = command
        Effect.none
          .thenRun { state: State =>
            val delay = context.settings.depositRetryDelay
            val message =
              s"""
                 |Deposit failed with an unexpected exception.
                 |This failure might occur for the deposit function's bug or breaking change.
                 |Though we have to recover this failure by human operations, this will retry deposit again after ${delay.toString}.
                 |""".stripMargin
            context.logError(state, cause, message)
            context.timers.startSingleTimer(TimerKeys.RetryDeposit, DepositToDestination, delay)
          }
      }

      override def applyEvent(context: Context, event: DomainEvent): State = {
        event match {
          case _: BalanceExceeded =>
            RefundingToSource(
              sourceAccountNo,
              destinationAccountNo,
              remittanceAmount,
              withdrawalTransactionId,
              depositTransactionId,
              refundTransactionId,
              RefundingToSource.RefundReason.BalanceExceeded,
            )
          case _: DepositSucceeded =>
            Succeeded(
              sourceAccountNo,
              destinationAccountNo,
              remittanceAmount,
              withdrawalTransactionId,
              depositTransactionId,
              refundTransactionId,
            )
          case _ =>
            throwIllegalStateException(context, event)
        }
      }

    }

    object RefundingToSource {
      sealed trait RefundReason
      object RefundReason {
        case object BalanceExceeded extends RefundReason
      }
    }
    final case class RefundingToSource(
        sourceAccountNo: AccountNo,
        destinationAccountNo: AccountNo,
        remittanceAmount: BigInt,
        withdrawalTransactionId: TransactionId,
        depositTransactionId: TransactionId,
        refundTransactionId: TransactionId,
        refundReason: RefundingToSource.RefundReason,
    )(implicit
        val appRequestContext: AppRequestContext,
    ) extends TransactionState {

      import RefundingToSource._

      override def applyCommand(context: Context, command: Command): Effect = command match {
        case RefundToSource =>
          applyRefundToSource(context)
        case command: RefundCompleted =>
          applyRefundCompleted(context, command)
        case command: RefundFailedWithUnexpectedException =>
          applyRefundFailedWithUnexpectedException(context, command)
        case _: Remit =>
          Effect.stash()
        case Stop =>
          Effect.stop()
        case command: InspectState =>
          applyInspectState(command)
        case _ =>
          ignoreUnexpectedCommand(context, command)
      }

      private def applyRefundToSource(context: Context): Effect = {
        Effect.none.thenRun { _ =>
          val refund =
            context.bankAccountApplication.refund(
              sourceAccountNo,
              refundTransactionId,
              withdrawalTransactionId,
              remittanceAmount,
            )
          context.actorContext.pipeToSelf(refund) {
            case Success(result) =>
              RefundCompleted(result)
            case Failure(cause) =>
              RefundFailedWithUnexpectedException(cause)
          }
        }
      }

      private def applyRefundCompleted(context: Context, command: RefundCompleted): Effect = {
        val RefundCompleted(result) = command
        result match {
          case RefundResult.Succeeded(_) =>
            val refunded = RefundSucceeded(
              sourceAccountNo,
              refundTransactionId,
              withdrawalTransactionId,
              remittanceAmount,
            )
            Effect
              .persist(refunded)
              .thenRun { newState =>
                context.logEvent(newState, this, refunded)
                context.callbacks.onRefundSucceeded(context, this, newState)
              }

          case RefundResult.InvalidArgument =>
            Effect.none
              .thenRun { state: State =>
                val delay = context.settings.refundRetryDelay
                val message =
                  s"""
                     |Refund failed due to invalid argument(s).
                     |This failure could occur for several reasons.
                     |One of them is that the refundTransactionId is already used for another transaction.
                     |Though we might have to recover this failure by human operations, this will retry refund again after ${delay.toString}.
                     |""".stripMargin
                context.logError(state, message)
                context.timers.startSingleTimer(TimerKeys.RetryDeposit, RefundToSource, delay)
              }
          case RefundResult.Timeout =>
            Effect.none
              .thenRun { state: State =>
                val delay = context.settings.refundRetryDelay
                context.logWarn(
                  state,
                  s"Refund failed due to a timeout. This will retry refund again after ${delay.toString}.",
                )
                context.timers.startSingleTimer(TimerKeys.RetryDeposit, RefundToSource, delay)
              }
        }
      }

      private def applyRefundFailedWithUnexpectedException(
          context: Context,
          command: RefundFailedWithUnexpectedException,
      ): Effect = {
        val RefundFailedWithUnexpectedException(cause) = command
        Effect.none
          .thenRun { state: State =>
            val delay = context.settings.refundRetryDelay
            val message =
              s"""
                 |Refund failed with an unexpected exception.
                 |This failure might occur for the refund function's bug or breaking change.
                 |Tough we might have to recover this failure by human operations, this will retry refund again after ${delay.toString}.
                 |""".stripMargin
            context.logError(state, cause, message)
            context.timers.startSingleTimer(TimerKeys.RetryDeposit, RefundToSource, delay)
          }
      }

      override def applyEvent(context: Context, event: DomainEvent): State = {
        event match {
          case _: RefundSucceeded =>
            val failureReply = refundReason match {
              case RefundReason.BalanceExceeded => ExcessBalance
            }
            Failed(
              sourceAccountNo,
              destinationAccountNo,
              remittanceAmount,
              withdrawalTransactionId,
              depositTransactionId,
              refundTransactionId,
              failureReply,
            )
          case _ =>
            throwIllegalStateException(context, event)
        }
      }

    }

    sealed trait TransactionCompletedState extends TransactionState {
      def applyPassivate(context: Context): Effect = {
        Effect.none.thenRun { _: State =>
          context.shard ! ClusterSharding.Passivate(context.actorContext.self)
        }
      }
    }

    final case class Succeeded(
        sourceAccountNo: AccountNo,
        destinationAccountNo: AccountNo,
        remittanceAmount: BigInt,
        withdrawalTransactionId: TransactionId,
        depositTransactionId: TransactionId,
        refundTransactionId: TransactionId,
    )(implicit
        val appRequestContext: AppRequestContext,
    ) extends TransactionCompletedState {
      override def applyCommand(context: Context, command: Command): Effect = command match {
        case CompleteTransaction =>
          Effect.unstashAll()
        case remitCommand: Remit =>
          if (isValidCommand(remitCommand)) {
            Effect.none.thenReply(remitCommand.replyTo) { _: State => RemitSucceeded }
          } else {
            Effect.none.thenReply(remitCommand.replyTo) { _: State => InvalidArgument }
          }
        case Passivate =>
          applyPassivate(context)
        case Stop =>
          Effect.stop()
        case command: InspectState =>
          applyInspectState(command)
        case _ =>
          ignoreUnexpectedCommand(context, command)
      }
      override def applyEvent(context: Context, event: DomainEvent): State = {
        throwIllegalStateException(context, event)
      }
    }

    final case class Failed(
        sourceAccountNo: AccountNo,
        destinationAccountNo: AccountNo,
        remittanceAmount: BigInt,
        withdrawalTransactionId: TransactionId,
        depositTransactionId: TransactionId,
        refundTransactionId: TransactionId,
        failureReply: RemitFailed,
    )(implicit
        val appRequestContext: AppRequestContext,
    ) extends TransactionCompletedState {
      override def applyCommand(context: Context, command: Command): Effect = command match {
        case CompleteTransaction =>
          Effect.unstashAll()
        case remitCommand: Remit =>
          if (isValidCommand(remitCommand)) {
            Effect.none.thenReply(remitCommand.replyTo) { _: State => failureReply }
          } else {
            Effect.none.thenReply(remitCommand.replyTo) { _: State => InvalidArgument }
          }
        case Passivate =>
          applyPassivate(context)
        case Stop =>
          Effect.stop()
        case command: InspectState =>
          applyInspectState(command)
        case _ =>
          ignoreUnexpectedCommand(context, command)
      }
      override def applyEvent(context: Context, event: DomainEvent): State = {
        throwIllegalStateException(context, event)
      }
    }

    final case class EarlyFailed(
        sourceAccountNo: AccountNo,
        destinationAccountNo: AccountNo,
        remittanceAmount: BigInt,
    )(implicit
        val appRequestContext: AppRequestContext,
    ) extends TransactionCompletedState {
      override def applyCommand(context: Context, command: Command): Effect = command match {
        case CompleteTransaction =>
          Effect.unstashAll()
        case remitCommand: Remit =>
          Effect.none.thenReply(remitCommand.replyTo) { _: State => InvalidArgument }
        case Passivate =>
          applyPassivate(context)
        case Stop =>
          Effect.stop()
        case command: InspectState =>
          applyInspectState(command)
        case _ =>
          ignoreUnexpectedCommand(context, command)
      }
      override def applyEvent(context: Context, event: DomainEvent): State = {
        throwIllegalStateException(context, event)
      }
    }

  }

}