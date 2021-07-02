# Template

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
