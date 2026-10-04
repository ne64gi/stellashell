# StellaShell Platform Bridge

`:platform-bridge` は Shizuku の shell-UID user service と、その Android framework adapter を所有する。
Android library（compile SDK 35 / min SDK 30 / Java 17）として AIDL を生成する。

```text
app → platform-bridge → core
```

app は Shizuku 接続、UI、保存設定、runtime selection を所有する。
この module は app に依存せず、app resource・preferences・View にアクセスしない。
Shizuku client SDK は app 側に残る。ここには Binder service、framework task API、
pin、mouse / keyboard / primary-screen lease、capture、display session、density adapter を置く。

## Package と互換性

Android namespace は `net.fuyumori.stellashell.platform.bridge`。
Java / AIDL package は既存の **`net.fuyumori.stellashell`** を維持する。
これは Shizuku service FQCN、AIDL wire descriptor、および同 package の隔離 AndroidTest fixture が
package-private framework adapter を利用する既存契約を保つための意図的な legacy namespace である。
package 分離のために platform 内部 API を public 化しない。

- `IDesktopBridge` の declaration・明示 transaction ID・parameter・戻り値は不変。
- `DesktopBridgeService` の constructor、同期、`OK` / `ERROR:` 応答、lease cleanup は不変。
- HOME / PiP / mouse / pin の policy、OEM guard、task identity、app 自己除外 literal は不変。
- app の保存設定や選択 state は読むのではなく、既存 Binder command で受け取る。
- AndroidTest の fake / probe は app 側に残す。module 化を端末試験済みとは扱わない。

## Platform-local adapters

`BridgeDisplayTargets` は app の `Displays.available/ids/allIds` と同じ public display inventory を持つ。
valid・non-private の外部 display を ID 順に列挙し、valid・non-private の本体 display 0 を最後に追加する。
primary display の許可は引き続き呼び出し側の `primaryMode` と core policy が判定する。

`BridgeWindowGeometry` は app の `WorkArea.clamp(Rect, Rect)` と同じ Rect adapter。
指定 area の原点へ平行移動して core の integer clamp に渡し、結果を元の原点へ戻す。
work area の取得・選択・予約領域の決定は引き継がず、既存 caller が渡す Rect のみを使う。

両 helper は package-private。抽出元の計算と判定を変更していない。

## 検証

repository の integration check で library / app / AndroidTest の compile と lint を確認する。
APK build、install、実端末での Binder / framework 動作確認は別の検証段階である。

```bash
./gradlew :platform-bridge:assembleDebug :platform-bridge:lintDebug
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```
