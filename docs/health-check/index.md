# ヘルスチェック機能

銀行口座アプリケーションではヘルスチェック機能を実装しており、アプリケーション起動時にヘルスチェックに失敗する場合はアプリケーションの起動を中断しシャットダウンを行います。
また、[Akka Management](https://doc.akka.io/docs/akka-management/1.0.10/akka-management.html) を利用してヘルスチェック用のAPIを用意しています。

現状のヘルスチェックではRDBにアクセスできることのみを確認しています。

## ソースコード
* [JDBCHealthCheck.scala](../../app/application/src/main/scala/myapp/application/util/healthcheck/JDBCHealthCheck.scala)
  * Akka Management に登録するヘルスチェックをおこなうクラス
  * Akka Management に登録できるヘルスチェックは`Function0[Future[Boolean]]`を拡張したものかつ、コンストラクタ引数がないか`ActorSystem`型の値を1つ引数にもつものしか許されない。
  * ヘルスチェックのためのDB接続確認はアプリケーションの他の機能でも使われているDBのコネクションプールを使う必要があるが、上記の制約からコンストラクタ引数を通して [JDBCService](../../app/read-model/src/main/scala/myapp/readmodel/JDBCService.scala) のインスタンスをうけとることはできないので、DBの接続確認は`Receptionist`に登録されたアクターを通して行っている。
  * ヘルスチェックの定義について: https://doc.akka.io/docs/akka-management/1.0.10/healthchecks.html#defining-a-health-check
  * Receptionist: https://doc.akka.io/docs/akka/2.6.12/typed/actor-discovery.html#receptionist
* [JDBCHealthCheckApplication.scala](../../app/application/src/main/scala/myapp/application/util/healthcheck/JDBCHealthCheckApplication.scala)
  * 簡単なSQLクエリでDBの接続確認を行う関数を定義している。
* [JDBCHealthCheckFailureShutdown.scala](../../app/application/src/main/scala/myapp/application/util/healthcheck/JDBCHealthCheckFailureShutdown.scala)
  * ヘルスチェックが失敗したことによってアプリケーションがシャットダウンされることを表すもの。
  * Coordinated Shutdown: https://doc.akka.io/docs/akka/2.6.12/coordinated-shutdown.html
* [JDBCHealthCheckService.scala](../../app/application/src/main/scala/myapp/application/util/healthcheck/JDBCHealthCheckService.scala)
  * DBの接続状況を確認するアクターを `Receptioninst` に登録する。
  * 登録されたアクターは一定間隔で`JDBCHealthcheckApplication::check`を呼び出し、その結果に応じて`healthy`と`unhealthy`で状態遷移する。
* [JDBCHealthCheckServiceSetting.scala](../../app/application/src/main/scala/myapp/application/util/healthcheck/JDBCHealthCheckServiceSetting.scala)
* [JDBCHealthCheckSetting.scala](../../app/application/src/main/scala/myapp/application/util/healthcheck/JDBCHealthCheckSetting.scala)
* [AppGuardian.scala](../../app/entrypoint/src/main/scala/myapp/entrypoint/AppGuardian.scala)
  * ヘルスチェックをおこない、ヘルスチェックが成功した場合はクラスターに参加するために`ClusterBootstrap(system).start()`が実行される。
    ヘルスチェックが失敗した場合はクラスターに参加せずアプリケーションをシャットダウンするために`CoordinatedShutdown(system).run(JDBCHealthCheckFailureShutdown)`が実行される。
  * Akka Cluster Bootstrap: https://doc.akka.io/docs/akka-management/1.0.10/bootstrap/index.html
* [AppGuardianSetting.scala](../../app/entrypoint/src/main/scala/myapp/entrypoint/AppGuardianSetting.scala)
