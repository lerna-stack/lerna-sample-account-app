package myapp.application.projection

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorSystem, Behavior }
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.projection.ProjectionBehavior
import myapp.application.projection.deposit.DepositProjection
import myapp.application.projection.transaction.BankTransactionEventHandler
import myapp.readmodel.JDBCService
import myapp.utility.tenant.AppTenant
import wvlet.airframe.Session

class ProjectionService(session: Session, system: ActorSystem[Nothing]) {

  @SuppressWarnings(Array("org.wartremover.contrib.warts.MissingOverride"))
  private def projectionBehaviors(tenant: AppTenant): Seq[Behavior[ProjectionBehavior.Command]] = {
    import wvlet.airframe._
    val childDesign  = newDesign.bind[AppTenant].toInstance(tenant)
    val childSession = session.newChildSession(childDesign)
    val setup = AppEventHandler.BehaviorSetup(
      system = system,
      dbConfig = childSession.build[JDBCService].dbConfig(tenant),
      tenant = tenant,
    )

    /** 新規の EventHandler は下記の Seq に追加する。
      * Seq の index と、EventHandler の対応は全ノードで同一である必要がある。
      * 対応がずれた場合は（本来は 1 箇所でのみ起動すべき）同種の EventHandler が複数ノードで起動しデータ更新が競合する可能性がある。
      * EventHandler を廃止する場合は廃止する EventHandler の代わりに Behaviors.stopped （何も処理しない Behavior）を配置して
      * 全ノードで index と EventHandler の対応がずれないようにする。
      *
      * Behaviors.stopped は全ノードを停止できるタイミングで削除可能。
      */
    Seq(
      Behaviors.stopped, /* 廃止: childSession.build[BankEventHandler].createBehavior() */
      childSession.build[BankTransactionEventHandler].createBehavior(setup),
      childSession.build[DepositProjection].createBehavior(setup),
    )
  }

  def start(): Unit = {
    AppTenant.values.foreach { tenant: AppTenant =>
      val behaviors = projectionBehaviors(tenant)
      ShardedDaemonProcess(system).init(
        name = s"ProjectionService:${tenant.id}",
        numberOfInstances = behaviors.size,
        // index が追加されてローリングアップデート中に例外が発生しないように、index に要素が存在しない場合は stopped を返す
        behaviorFactory =
          i => if (behaviors.isDefinedAt(i)) behaviors(i) else Behaviors.stopped[ProjectionBehavior.Command],
        stopMessage = ProjectionBehavior.Stop,
      )
    }
  }
}
