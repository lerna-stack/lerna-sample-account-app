# ヘルスチェック機能

銀行口座アプリケーションではヘルスチェック機能を実装しており、アプリケーション起動時にヘルスチェックに失敗する場合はアプリケーションの起動を中断しシャットダウンを行います。
また、[Akka Management](https://doc.akka.io/docs/akka-management/1.1.1/akka-management.html) を利用してヘルスチェック用のAPIを用意しています。

現状のヘルスチェックではRDBにアクセスできることのみを確認しています。

## ソースコード
* [JDBCHealthCheck.scala](../../app/application/src/main/scala/myapp/application/util/healthcheck/JDBCHealthCheck.scala)
* [JDBCHealthCheckApplication](../../app/application/src/main/scala/myapp/application/util/healthcheck/JDBCHealthCheckApplication.scala)
* [JDBCHealthCheckFailureShutdown](../../app/application/src/main/scala/myapp/application/util/healthcheck/JDBCHealthCheckFailureShutdown.scala)
* [JDBCHealthCheckService.scala](../../app/application/src/main/scala/myapp/application/util/healthcheck/JDBCHealthCheckService.scala)
* [JDBCHealthCheckServiceSettings.scala](../../app/application/src/main/scala/myapp/application/util/healthcheck/JDBCHealthCheckServiceSetting.scala)
* [AppGuardian.scala](../../app/entrypoint/src/main/scala/myapp/entrypoint/AppGuardian.scala)
