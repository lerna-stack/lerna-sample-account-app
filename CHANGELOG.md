# 変更履歴

myapp に関する注目すべき変更はこのファイルで文書化されます。

このファイルの書き方に関する推奨事項については、[Keep a Changelog](https://keepachangelog.com/ja/1.0.0/) を確認してください。

## Unreleased

### Changed
- 口座番号とテナントに紐づく入出金明細を出力する実装を追加 [PR#32](https://github.com/lerna-stack/lerna-sample-account-app/pull/32),
[PR#34](https://github.com/lerna-stack/lerna-sample-account-app/pull/34)
  - 口座番号とテナントに紐づく時系列順に並べられた取引一覧を取得できます
  - `offset`と`limit`パラメータを利用して取引一覧の取得位置と個数を指定できます

## [v2021.10.0] - 2021-10-22
[v2021.10.0]: https://github.com/lerna-stack/lerna-sample-account-app/compare/v2021.7.0...v2021.10.0

- RDBMS を介して連携されるデータを Entity にインポートするサンプル実装を追加しました [PR#19](https://github.com/lerna-stack/lerna-sample-account-app/pull/19)
- 送金機能を実装しました [PR#29](https://github.com/lerna-stack/lerna-sample-account-app/pull/29)
  - 送金のアーキテクチャは [送金機能](docs/remittance-orchestrator/index.md) で確認できます

### Changed
- 送金機能の実装に向けて 入出金機能のAPI を変更しました [PR#25](https://github.com/lerna-stack/lerna-sample-account-app/pull/25)
  - 口座に残高上限を追加しました。残高上限は 10,000,000 です
  - 残高上限を超えるような入金(残高超過)は失敗します
  - 残高超過や残高不足であった場合に、`500 Internal Server Error` の代わりに `400 BadRequest` を返します
  - 残高確認、入金、出金でタイムアウトが発生した場合、`500 Internal Server Error` の代わりに `503 ServiceUnavailable` を返します
  - バッチ入金で残高超過が発生した場合、ERROR ログを出力して当該入金はスキップします
  - バッチ入金でタイムアウトが発生した場合、WARN ログを出力してバッチ入金処理を再起動します
- 送金機能の実装に向けて、返金機能を実装しました [PR#28](https://github.com/lerna-stack/lerna-sample-account-app/pull/28)

### Fixed
- Cassandra からイベントなどを読み込む際のプロファイルが書き込みの際に使われるプロファイルとは違うものになっていた問題を修正しました [PR#21](https://github.com/lerna-stack/lerna-sample-account-app/pull/21)

### Dependency Updates
- lerna-app-library 2.0.0 から 3.0.0 に更新しました
- wiremock-jre8 2.27.2 から 2.30.1 に更新しました  
  バイナリ互換性を維持しやすくするため、lerna-app-library が使用する wiremock-jre8 と同じバージョンとしています。


## [v2021.7.0] - 2021-7-16
[v2021.7.0]: https://github.com/lerna-stack/lerna-sample-account-app/releases/tag/v2021.7.0

- Initial release
