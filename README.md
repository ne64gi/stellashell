# StellaShell

*Turn your Android into a desktop — without replacing Android.*

<img src="docs/stellashell-icon.svg" width="96" alt="StellaShell star icon">

📱 **Phone** → 普通のAndroid + Stella Dock\
🖥 **Monitor** → Stella Desktop\
🔌 **Unplug** → そのままPhoneへ

スマホではいつものAndroid。モニターやscrcpyにつなげば、ウィンドウで作業できるデスクトップへ。外部画面との自動切替は設定で有効にできます（端末・アプリ依存）。

**[APKを入手](https://github.com/ne64gi/stellashell/releases)** · **[セットアップ](docs/setup/README.md)** · **[リリースノート](docs/RELEASES.md)**

## はじめる

Android 11以降。デスクトップの完全なウィンドウ操作には **Shizuku・freeform対応・重ねて表示の許可** が必要です。外部画面には端末の映像出力、またはPCのscrcpyを使います。

- [Phone / HOMEとDock](docs/PHONE-WORKSPACE.md)
- PCで使う：[Windows](tools/windows/README.md) / [Linux・macOS](tools/unix/README.md)
- [ユーザーガイド：日常操作・カスタマイズ](docs/USER-GUIDE.md)

Dockとタスクバーは別機能で、アプリのピン留めも別々です。本体タスクバーは任意（初期OFF）、外部画面のタスクバーは常設です。Dockは本体・外部画面で個別に表示を選べます（本体は初期ON、外部は初期OFF）。[操作の違い](docs/USER-GUIDE.md#dockとタスクバー)を参照してください。バーだけの大きさと、[画面全体の表示サイズ](docs/USER-GUIDE.md#画面全体の表示サイズ)は別々に調整できます。

開発中のプレリリースです。機能ごとの検証範囲・未確認事項はリリースノートへ。標準HOMEは勝手に変更しません。Termux / self-ADB :5555は任意です。

## 開発

日常の操作は[ユーザーガイド](docs/USER-GUIDE.md)、導入・接続・診断は[セットアップ](docs/setup/README.md)を参照してください。

ソースからのビルドにはJDK 17、Android SDK Platform 35／Build Tools 35.0.0を使います。`ANDROID_HOME`、またはローカルの `local.properties` の `sdk.dir` でSDKを指定します。Gradle 8.11.1はWrapperで取得します。

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APKは `app/build/outputs/apk/debug/app-debug.apk`（開発用署名）。ビルド成功は端末での検証完了を意味しません。

### CI

[Android checks](.github/workflows/android-checks.yml) はpushとPull Requestごとに、JDK 17／Gradle WrapperでJVMテスト、lint、debug APKとAndroidTest APKのビルドを実行します。AndroidTestはコンパイル確認だけで、エミュレーター・実機テストや配布は行いません。結果はGitHub Actionsの各実行で確認してください。

- [変更・検証範囲の入口](docs/RELEASES.md)
- [タスクAPIと機種別の調査](docs/TASK-API-PROVENANCE.md)
- [過去の開発履歴](docs/RELEASE-HISTORY.md)

日付や版が付いた資料は当時の記録です。現行仕様は現在のソースとユーザーガイド、後続の検証記録を優先します。物理USB出力、全OEM、Android 11の動作を一律に保証しません。

## Thanks & License

[Taskbar](https://github.com/farmerbb/Taskbar) · [Dextop](https://github.com/NarYuki/Dextop) · [scrcpy](https://github.com/Genymobile/scrcpy)

**GPL-3.0-or-later**（第三者部分を除く）。scrcpy由来の改変コードはApache-2.0の通知を保持しています。\
[LICENSE](LICENSE) · [第三者表記](THIRD_PARTY_NOTICES.md) · [NOTICE](NOTICE) · [監査・修正履歴](docs/LICENSE-REMEDIATION-2026-09-29.md)


Taskbar（Braden Farmer / contributors）とDextop（NarYuki / contributors）は設計・互換性調査の参考、scrcpy（Genymobile / Romain Vimont / contributors）は開発・検証ツールに加え本体画面消灯の改変コードの出典です。皆さんに感謝します。参考・謝辞と、取り込んだコード・依存資材のライセンスは区別しています。詳細な著作権・変更通知・参照コミットは[第三者表記](THIRD_PARTY_NOTICES.md)に記載し、必要な通知はAPKにも保持しています。各プロジェクトの公式な推奨・提携を示すものではありません。

本体・ドキュメント・同梱ヘルパーのGPL-3.0-or-laterへの整理は、過去の来歴問題そのものの解消を意味しません。[監査](docs/LICENSE-AUDIT-2026-09-29.md)・[修正履歴](docs/LICENSE-REMEDIATION-2026-09-29.md)を参照してください。

内部の状態管理・寿命・設定互換の境界は [Shellの状態と所有境界](docs/STATE-OWNERSHIP.md) を参照。
