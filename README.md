# lerna-sample-account-app

Lerna Stack 向けの「銀行口座」アプリケーションのサンプルです。

[akka-entity-replication](https://github.com/lerna-stack/akka-entity-replication) (HA library) を用いており、より上位の可用性を実現します。

## Provisioning environment

`docker-compse` を使用して開発環境を準備できます。

開発環境を構築するには次のコマンドを実行します。  
これによって `MariaDB` と `Cassandra` が起動します。

```shell
docker-compose up
```

開発環境で動くサーバ(`MariaDB`等)を停止するには次のコマンドを実行します。

```shell
docker-compose down
```

開発環境を破棄するには次のコマンドを実行します。  
このコマンドで開発環境のデータベースの内容がすべて破棄されます。

```shell
docker-compose down --volumes
```

## Getting started

次のコマンドを実行することで、
コンパイルとユニットテストを実行できます。
ユニットテストを実行するためには開発環境を構築しておく必要があります。

```shell
sbt clean test:compile test
```

アプリサーバを実行するには次の2つのコマンドを別々のシェルで実行します。

```shell
# アプリサーバ1を起動します
./scripts/start-app-1.sh
```

```
# アプリサーバ2を起動します
./scripts/start-app-2.sh
```

## API

アプリサーバ1(`127.0.0.1`)にリクエストを送る例です。

```shell
# OKが返ります
curl --silent --noproxy "*" http://127.0.0.1:9001/index

# アプリバージョンを取得します
# デフォルトでは unknown になっています
# 設定ファイルや環境変数で上書きすることができます
curl --silent --noproxy "*" http://127.0.0.1:9002/version

# アプリコミットハッシュを取得します
# デフォルトでは unknown になっています
# 設定ファイルや環境変数で上書きすることができます
curl --silent --noproxy "*" http://127.0.0.1:9002/commit-hash
```

アプリサーバ2(`127.0.0.2`)にリクエストを送る場合はIPアドレスを変更します。

```
curl --silent --noproxy "*" http://127.0.0.2:9001/index
curl --silent --noproxy "*" http://127.0.0.2:9002/version
curl --silent --noproxy "*" http://127.0.0.2:9002/commit-hash
```

ポート番号 `9001` は ユーザ向け機能、  
ポート番号 `9002` は 管理用機能を定義することを想定しています。

### Bank Accounts
#### 残高確認
- method: `GET`
- path: `/accounts/${accountNo}`
- headers
    - `X-Tenant-Id`: `${tenantId}`
        - `tenant-a`
        - `tenant-b`

```shell
curl \
    --silent \
    --show-error \
    --request 'GET' \
    --url 'http://127.0.0.1:9001/accounts/test33' \
    --header 'X-Tenant-Id: tenant-a'
```

#### 入金
- method: `GET`
- path: `/accounts/${accountNo}/deposit`
- (query) parameters
    - `transactionId` (string)
    - `amount` (number)
- headers
    - `X-Tenant-Id`: `${tenantId}`

```shell
curl \
    --silent \
    --show-error \
    --request 'POST' \
    --url "http://127.0.0.1:9001/accounts/test33/deposit?transactionId=$(date '+%s')&amount=600" \
    --header 'X-Tenant-Id: tenant-a'
```

#### 出金
- method: `GET`
- path: `/accounts/${accountNo}/withdraw`
- (query) parameters
    - `transactionId` (string)
    - `amount` (number)
- headers
    - `X-Tenant-Id`: `${tenantId}`

```shell
curl \
    --silent \
    --show-error \
    --request 'POST' \
    --url "http://127.0.0.1:9001/accounts/test33/withdraw?transactionId=$(date '+%s')&amount=500" \
    --header 'X-Tenant-Id: tenant-a'
```

## バッチ入金

MariaDB の `deposit_store` テーブルにレコードを挿入すると、そのデータを元に口座へ入金が行われます。
新規データは定期的にチェックされており、データを追加すると即座に入金されます。

次のコマンドを実行すると、`deposit_store` テーブルに大量のダミーデータを生成できます。
次の例で `1000` になっているデータ件数を変更すると、任意の量のデータを生成できます。

```shell
docker-compose exec mariadb1 import-deposit-store 1000
```
この処理は大まかに次のような流れで行われます。

![](docs/img/data-import.drawio.png)

- ①-②: データをどこまで取り込んだかを示す offset を取得
- ③: offset よりも新しいデータを要求
- ④: offset よりも新しいデータが無いか定期的にチェック（ポーリング）
- ⑤-⑥: offset よりも新しいデータがあればそのデータを取得
- ⑦: 取得したデータを Entity のコマンドに変換して入金を行う
- ⑧: 入金した結果を受け取り
- ⑨: 入金が成功したところまでの offset を保存
- ④-⑨ を繰り返す

**NOTE**
- `DepositProjection` の実装には [Akka Projection](https://doc.akka.io/docs/akka-projection/current/overview.html) を用います
- `akka_projection_offset_store` と `AccountEntity` へのコマンド送信には原子性（Atomicity）がありません。
  コマンド送信だけが成功して offset の保存に失敗するということが起こる可能性があります。
  この場合、同じコマンドが重複して再送される可能性があるため、`AccountEntity` で重複実行を防ぐ実装が必要です。
  各入金を識別するため `TransactinId` という識別子を導入します
- `AccountEntity` 内部で `TransactionId` を保持する方法は 2 種類考えられます
  1. コマンドの入力元ごとに保持する

      HTTP API や Projection などコマンドの入力元ごとに分けて `TransactionId` を保持します。
      確実にコマンドの重複を回避できますが、メモリ使用量が増え、Entity の実装が複雑になるというデメリットがあります。

  2. 入力元を意識せず保持する

      HTTP API や Projection などコマンドの入力元を意識せず全ての `TransactionId` を同様に扱います。
      メモリ使用量が抑えられ、Entity の実装もシンプルになりますが、重複が発生しないよう UUIDv4 など衝突のリスクが低い識別子を用いるか、
      `TransactionId` に重複が発生しないよう発行元の調整を行う必要があります。また、`TransactionId` を持てる量には上限があるため、
      一部のコマンドの入力元が大量の `TransactionId` を単一の Entity に送信するとコマンドの重複実行が起きるリスクが増えます。
      このサンプルアプリケーションではこちらの方法を採用しています。

## テストカバレッジ を取得する

次のコマンドを実行することで、テストカバレッジを取得できます。

```shell
sbt take-test-coverage
```

テストカバレッジは、`./target/scala-2.13/scoverage-report`に出力されます。

## データベースのスキーマを更新する

データベースのスキーマを追加するには次の手順を実行してください。

### 開発環境を破棄する

開発環境を破棄するため次のコマンドを実行してください。

```shell
docker-compose down --volumes
```

### SQL ファイルを追加する
[./docker/mariadb/initdb](./docker/mariadb/initdb) に
テーブル作成やマスターデータ追加などのSQLファイルを追加します。

### 開発環境を再構築する

開発環境を再構築するため次のコマンドを実行してください。

```shell
docker-compose up
```

### Slick スキーマ定義コードを生成する
SQLファイルを追加したあと、対応するSlickのスキーマ定義コードを自動生成するため次のコマンドを実行します。

```shell
sbt slick-codegen/run
```


## RPM パッケージをビルドする
RPM パッケージを作成するためには、次のコマンドを実行します。

```shell
docker-compose run --rm sbt-rpmbuild clean rpm:packageBin
```

RPM ファイルは、`target/rpm/RPMS/noarch/` に作成されます。

### RPM パッケージビルド の注意事項

- RPM パッケージのビルドでは、プロジェクトが `git` で管理されており、
RPM パッケージに含める内容はすべてコミット済みであることが想定されています。
RPM パッケージをビルドする前に、この条件が満たされていることを確認してください。

- RPM パッケージのビルドには [CHANGELOG.md](./CHANGELOG.md) ファイルが必要です。

## バージョン戦略
[Calendar Versioning — CalVer](https://calver.org/) `YYYY.MM.MICRO` を使用します。

## CHANGELOG

特筆すべき変更点は [CHANGELOG.md](CHANGELOG.md) から確認できます。
