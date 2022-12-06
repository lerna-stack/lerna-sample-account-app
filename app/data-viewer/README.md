# Cassandra データ閲覧ツール
HTTP server を起動して、HTTPリクエストすると Cassandra に保存されたデータ(Event, Snapshot)を取得・デシリアライズして確認する

## API
### Event
```
/{configPath}/event/{persistenceId}/{fromSequenceNr}?to={toSequenceNr}
```

- `persistenceId: String`: 取得する persistence-id
- `fromSequenceNr: Long`: 取得開始する sequence-number
- `toSequenceNr: Option[Long]`: デフォルト=`{fromSequenceNr}`

#### 例
- `/akka-entity-replication.raft.persistence.cassandra-tenant-a/event/raft:BankAccount-tenant-a:xxxxx:replica-group-1/0?to=10`
- `/akka-entity-replication.raft.persistence.cassandra-tenant-a/event/raft:BankAccount-tenant-a:xxxxx:replica-group-1/5`

### Snapshot
```
/{configPath}/event/{persistenceId}/{sequenceNr}
```

- `configPath: String`: 
  - 例: `akka-entity-replication.raft.persistence.cassandra-tenant-a`
- `persistenceId: String`: 取得する persistence-id
- `sequenceNr: Long`: 取得する sequence-number

#### 例
- `/akka-entity-replication.raft.persistence.cassandra-tenant-a/snapshot/SnapshotStore:BankAccount:xxxxx:replica-group-1/0`

## Config
### HTTP Server の IP:Port
```hocon
myapp.data-viewer.server {
  interface = "127.0.0.1"
  port = 8080
}
```

### Cassandra 接続先
```hocon
datastax-java-driver.basic {
  contact-points.0 = "127.0.0.1:9042"
  load-balancing-policy.local-datacenter = "datacenter1"
}
datastax-java-driver.advanced.auth-provider {
  username = "cassandra" // CHANGEME
  password = "cassandra" // CHANGEME
}
```

#### 必要な権限
読み取り権限のみ

## packaging
1. package
    ```shell
    sbt data-viewer/universal:packageBin
    ```
1. `./app/data-viewer/target/universal/` に作成されたzipを適当な場所に解凍
1. 実行設定
    - 必要に応じて `./conf/application.ini` の設定を変更
1. `./bin/` フォルダ内の実行ファイルを実行
