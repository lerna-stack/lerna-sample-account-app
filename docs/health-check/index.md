# ヘルスチェック機能
銀行口座アプリケーションではアプリケーション起動時のヘルスチェックと実行中のヘルスチェックの2種類を実装しています。

現状のヘルスチェックではRDBにアクセスできることのみを確認しています。

## ヘルスチェックが行われるタイミング

### 起動時
起動時のヘルスチェックではアプリケーションが依存するサービスが利用可能な状態になっているか確認し、
利用できない場合はアプリケーションを一旦停止させます。
アプリケーションを停止させることで、Systemdなどのサービスマネージャーのプロセス再起動と組み合わせて、アプリケーション起動を初期化処理からリトライさせることができます。

起動時のヘルスチェックはアプリケーションの初期化処理をおこなう`AppGuardian`で実装されており、
`AppGuardianSetting`で設定されているリトライ回数を超えてヘルスチェックに失敗するとアプリケーションを停止する仕組みになっています。

### 実行中
実行時のヘルスチェックはアプリケーションがロードバランサーにリクエストを受付可能かどうかを通知するために利用されます。
実行中のヘルスチェックは
[Akka Management](https://doc.akka.io/docs/akka-management/1.0.10/akka-management.html) が提供するヘルスチェック用APIを通じてチェックされます。

## RDBのヘルスチェック
RDBのヘルスチェックは起動時のヘルスチェックも実行中のヘルスチェックも`JDBCHealthCheck`を通して行われます。
`JDBCHealthCheck`を呼び出すと、業務処理で利用するコネクションプールの状態を常時監視する`JDBCHealthCheckService`から最新のステータスを取得できます。
実際に業務処理で利用するコネクションプールの問題を検知するため、`JDBCHealthCheckService`が監視するコネクションプールと業務処理で利用するコネクションプールは同一のインスタンスになっています。



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
