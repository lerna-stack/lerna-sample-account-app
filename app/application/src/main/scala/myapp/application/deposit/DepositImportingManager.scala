package myapp.application.deposit

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity, EntityTypeKey }
import akka.persistence.typed.PersistenceId
import akka.stream.CompletionStrategy
import akka.stream.scaladsl.{ Keep, Sink }
import akka.stream.typed.scaladsl.{ ActorFlow, ActorSource }
import akka.util.Timeout
import myapp.adapter.Cursor
import myapp.application.account.AccountEntityBehavior
import myapp.application.serialize.KryoSerializable

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

private[deposit] object DepositImportingManager {

  final class Setup(
      val region: ActorRef[ShardingEnvelope[AccountEntityBehavior.Command]],
      val entityId: String,
  )

  sealed trait Command

  final case class Import()                          extends Command with KryoSerializable
  final case class GotCursor(cursor: Option[Cursor]) extends Command
  final case class GetCursorFailed(ex: Throwable)    extends Command
  final case class ImportingSucceeded()              extends Command
  final case class ImportingFailed(ex: Throwable)    extends Command

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey("DepositImportingManager")
}

private[deposit] class DepositImportingManager(
    depositCursorStoreBehavior: DepositCursorStoreBehavior,
    depositSourceBehavior: DepositSourceBehavior,
) {
  import DepositImportingManager._

  def shardRegion(
      system: ActorSystem[_],
      region: ActorRef[ShardingEnvelope[AccountEntityBehavior.Command]],
  ): ActorRef[ShardingEnvelope[Command]] =
    ClusterSharding(system).init(Entity(TypeKey) { entityContext =>
      createBehavior(region, entityContext.entityId)
    })

  def createBehavior(
      region: ActorRef[ShardingEnvelope[AccountEntityBehavior.Command]],
      entityId: String,
  ): Behavior[Command] =
    ready(
      new Setup(
        region,
        entityId,
      ),
    )

  private[this] def ready(setup: Setup): Behavior[Command] =
    Behaviors.setup { context =>
      implicit val timeout: Timeout = Timeout(10.seconds)

      Behaviors.receiveMessage {

        case Import() =>
          val cursorStore = context.spawn(
            depositCursorStoreBehavior.createBehavior(PersistenceId.of(TypeKey.name + "CursorStore", setup.entityId)),
            "cursorStore",
          )
          context.ask(cursorStore, DepositCursorStoreBehavior.GetCursor) {
            case Success(value) => GotCursor(value.cursor)
            case Failure(ex)    => GetCursorFailed(ex)
          }
          importing(setup, cursorStore)

        case _: GotCursor          => Behaviors.unhandled
        case _: GetCursorFailed    => Behaviors.unhandled
        case _: ImportingSucceeded => Behaviors.unhandled
        case _: ImportingFailed    => Behaviors.unhandled
      }
    }

  private[this] def importing(
      setup: Setup,
      cursorStore: ActorRef[DepositCursorStoreBehavior.Command],
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {

        case GotCursor(cursor) =>
          context.log.info("Got cursor: {}", cursor)
          val source = context.spawn(depositSourceBehavior.createBehavior(cursor), "source")
          context.pipeToSelf(start(source, cursorStore, setup.region)(context.system)) {
            case Success(_)  => ImportingSucceeded()
            case Failure(ex) => ImportingFailed(ex)
          }
          Behaviors.same

        case GetCursorFailed(ex) =>
          context.log.warn("Get cursor failed", ex)
          context.scheduleOnce(3.seconds, context.self, Import()) // retry
          cursorStore ! DepositCursorStoreBehavior.Stop()
          ready(setup)

        case ImportingSucceeded() =>
          context.log.info("Importing completed")
          cursorStore ! DepositCursorStoreBehavior.Stop()
          ready(setup)

        case ImportingFailed(ex) =>
          context.log.warn("Importing failed", ex)
          cursorStore ! DepositCursorStoreBehavior.Stop()
          ready(setup)

        case Import() =>
          context.log.info("Deposits are already importing")
          Behaviors.same
      }
    }

  private[this] def start(
      depositSource: ActorRef[DepositSourceBehavior.Command],
      cursorStore: ActorRef[DepositCursorStoreBehavior.Command],
      region: ActorRef[ShardingEnvelope[AccountEntityBehavior.Command]],
  )(implicit system: ActorSystem[_]): Future[Done] = {

    val source =
      ActorSource.actorRefWithBackpressure[DepositSourceBehavior.DemandReply, DepositSourceBehavior.Ack](
        ackTo = depositSource,
        ackMessage = DepositSourceBehavior.Ack(),
        completionMatcher = {
          // バッファにある要素を処理してから終了
          case _: DepositSourceBehavior.Completed => CompletionStrategy.draining
        },
        failureMatcher = {
          case f: DepositSourceBehavior.Failed => f.ex
        },
      )

    implicit val timeout: Timeout = Timeout(10.seconds)

    val (receiver, done) =
      source
        .collectType[DepositSourceBehavior.Deposits]
        .mapConcat(_.deposits.toVector)
        .throttle(100, per = 1.second)
        .via(ActorFlow.ask(region) { (e, replyTo: ActorRef[AccountEntityBehavior.DepositReply]) =>
          ShardingEnvelope(e.accountNo, AccountEntityBehavior.Deposit(e.cursor, e.amount, replyTo))
        })
        .collectType[AccountEntityBehavior.DepositReply]
        .map { deposited =>
          cursorStore ! DepositCursorStoreBehavior.SaveCursor(deposited.cursor)
          deposited
        }
        .toMat(Sink.ignore)(Keep.both)
        .run()
    depositSource ! DepositSourceBehavior.Start(limit = 1000, receiver)
    done
  }
}
