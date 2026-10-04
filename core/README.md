# StellaShell Core

Android に依存しない policy・geometry・session model の Java 17 module。
アプリからは `implementation(project(":core"))` で利用する。

## 責務

| Package (`net.fuyumori.stellashell.core.*`) | 所有するもの |
| --- | --- |
| `layout` | window・dock・widget・wallpaper・shortcut の座標計算、chrome 遮蔽 |
| `navigation` | 操作バーの局所倍率、画面 presentation 判定 |
| `launch` | launch policy、per-activity profile、起動領域の計画 |
| `tasks` | task mode・pin 対応判定、workspace operation の世代と rollback |
| `display` | density transaction、HOME output recovery、仮想 display session の識別 |
| `input` | mouse routing の所有 lease と retryable cleanup |
| `settings` | global setting の限定 transaction と正確な復元 |
| `assets` | font collection directory の bounded 読取 |
| `search` | 検索先 URL の検証と検索語の UTF-8 encoding |

## 依存境界

- Production source は JDK のみを利用する。Android、UI、Binder、JSON、app module の型には依存しない。
- Platform 操作は `ScreenScalePolicy.Store`、`SettingTransaction.Store`、`MouseRoutingLease.Backend` などの小さい port から app 側へ委ねる。
- `public` は module 外 consumer が利用する契約。計算 helper・検証 helper・所有 state は package-private / private のままにする。stateless utility は生成できない。
- Package は責務を表す。共通化のために Android 型を持ち込まず、platform adapter は app 側へ残す。
- `TaskSnapshot` の Android/JSON 依存は app に残す。検索先の URL policy は `search`、検索設定の保存と Start の操作寿命は `feature-search` に分ける。
- 起動 profile は immutable `LaunchProfileSnapshot` と項目別 command の `LaunchProfileOwner` が正本。保存は `LaunchProfileStore` port へ委ね、同じ backing store の adapter は同じ更新 lock を使う。

## 検証

既存の純 JVM 19 test classes（96 test methods）を責務別 package へ移動。
geometry、routing 拒否、保存済み bounds、density の target identity と exact rollback、
workspace 世代、mouse ownership、setting 復元の既存 assertion は維持する。
追加された契約には同じ module で振る舞いを検証する。

```bash
./gradlew :core:test
```

module 境界の fitness と Android consumer の compile/lint は repository 全体の検証で確認する。
JVM 検証を APK install・実端末検証と同一扱いにはしない。
