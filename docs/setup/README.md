# セットアップの入口

目的別に手順を分けています。**全部を実施する必要はありません。**

| やりたいこと | 読む手順 | 完了の目印 |
| --- | --- | --- |
| 通常のStellaShellセットアップ | [① ShizukuとStellaShell](01-shizuku.md) | Shizuku接続済み・操作バー許可済み |
| Android単体から自己ADBを使う（上級者・任意） | [② Termux / self-ADB :5555](02-termux-self-adb.md) | `127.0.0.1:5555` が `device` |
| PCに独立したデスクトップを表示する | [③ scrcpy / Windows・Linux・macOS](03-scrcpy.md) | 本体とは別の画面にStellaShellのバー |

- USBモニターを使う：① → モニター接続 → 外部画面で開始。
- タブレット本体で使う：① → 本体画面で開始（実験的）。
- PCに表示する：① + ③。②は不要です。USB ADBや通常のワイヤレスデバッグも使えます。
- 自己ADBで固定ポート運用する：② → ①のShizuku起動・権限付与 → 必要なら③。

**役割は別です。** Shizukuは他アプリのウィンドウ操作に必要な権限の橋渡し、ADBはデバッグ接続、scrcpyは画面転送と仮想画面の作成を担います。ADBが接続できてもShizukuは自動で起動しません。

Shizukuなしの動作範囲は [Capability Matrix](../LIMITED-MODE-0.7.md) を参照。現行0.7の初回セットアップとしてLimited Modeを推奨するものではありません。
外観を整える場合は [おすすめフォント](../FONTS.md) へ。
