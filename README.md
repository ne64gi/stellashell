# StellaShell

*Turn your Android into a desktop — without replacing Android.*

<img src="docs/stellashell-icon.svg" width="96" alt="StellaShell star icon">

📱 **Phone** → 普通のAndroid + Stella Sidebar\
🖥 **Monitor** → Stella Desktop\
🔌 **Unplug** → そのままPhoneへ

スマホではいつものAndroid。モニターやscrcpyにつなげば、ウィンドウで作業できるデスクトップへ。外部画面との自動切替は設定で有効にできます（端末・アプリ依存）。

**[APKを入手](https://github.com/ne64gi/stellashell/releases)** · **[セットアップ](docs/setup/README.md)** · **[リリースノート](docs/RELEASES.md)**

## はじめる

Android 11以降。デスクトップの完全なウィンドウ操作には **Shizuku・freeform対応・重ねて表示の許可** が必要です。外部画面には端末の映像出力、またはPCのscrcpyを使います。

- [Phone / HOMEとサイドバー](docs/PHONE-WORKSPACE.md)
- PCで使う：[Windows](tools/windows/README.md) / [Linux・macOS](tools/unix/README.md)
- [操作・カスタマイズ・ビルド](docs/USER-GUIDE.md)

開発中のプレリリースです。機能ごとの検証範囲・未確認事項はリリースノートへ。標準HOMEは勝手に変更しません。Termux / self-ADB :5555は任意です。

## Thanks & License

[Taskbar](https://github.com/farmerbb/Taskbar) · [Dextop](https://github.com/NarYuki/Dextop) · [scrcpy](https://github.com/Genymobile/scrcpy)

**GPL-3.0-or-later**（第三者部分を除く）。scrcpy由来の改変コードはApache-2.0の通知を保持しています。\
[LICENSE](LICENSE) · [第三者表記](THIRD_PARTY_NOTICES.md) · [謝辞](docs/USER-GUIDE.md#special-thanks--参考プロジェクト) · [監査・修正履歴](docs/LICENSE-REMEDIATION-2026-09-29.md)
