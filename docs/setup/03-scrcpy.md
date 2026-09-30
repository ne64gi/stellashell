# ③ scrcpyでStellaShellをPCに表示する

[手順の入口](README.md) · Windows例 / StellaShell 0.7

## 前提

[① ShizukuとStellaShell](01-shizuku.md)の権限・デスクトップ設定を済ませます。
PCに [scrcpy公式Windows配布](https://github.com/Genymobile/scrcpy/blob/master/doc/windows.md)を展開してください。ここではscrcpy 3.3.1のオプション仕様を使用します。`scrcpy.exe` だけでなくADB・DLL・serverを含む配布一式が必要です。

## 1. PCのADB接続を確認

### USBの場合（self-ADB不要）

USBデバッグを有効にし、データ通信対応ケーブルで接続。スマホの認証ダイアログでPCを許可します。

```powershell
.\adb.exe devices -l
```

対象が `device` なら接続済み。表示されたシリアルを次のコマンドに使います。

### ワイヤレスデバッグの場合（self-ADB不要）

スマホとPCを到達可能なネットワークへ接続し、Androidのペアリング画面を開きます。

```powershell
.\adb.exe pair 192.168.1.10:37125
# 表示されたペアリングコードを入力
.\adb.exe connect 192.168.1.10:40299
.\adb.exe -s 192.168.1.10:40299 get-state
```

IPとポートは例です。`pair` はペアリング画面のポート、`connect` は通常のワイヤレスデバッグ画面のポートです。Shizukuがペアリング済みでもPCは別に認証が必要です。

### TCP 5555を用意済みの場合

[② self-ADB](02-termux-self-adb.md)などで有効化しているなら：

```powershell
.\adb.exe connect 192.168.1.10:5555
.\adb.exe -s 192.168.1.10:5555 get-state
```

`device` になるまでscrcpyへ進まないでください。TCP接続が成功してもShizuku実行中とは限りません。

## 2. 独立した仮想画面を作る

```powershell
.\scrcpy.exe -s 192.168.1.10:5555 --keyboard=uhid --new-display=1920x1080/160 --display-ime-policy=local --no-vd-system-decorations --start-app=net.fuyumori.stellashell
```

`-s` の値を接続済みのシリアル／IP:ポートへ置き換えます。USB接続でも同じオプションを利用できます。

- `--new-display`：本体のミラーではなく別画面を作成。
- `1920x1080/160`：解像度とDPI。値を変更して表示サイズを調整。
- `--start-app`：その画面でStellaShellを起動。
- `--no-vd-system-decorations`：仮想画面のAndroidシステム装飾を非表示。
- PCで全画面にしたければ `--fullscreen` を追加。

StellaShellのバーと壁紙が見えれば画面作成は成功です。電卓などでウィンドウ起動も確認してください。Stella側の「外部画面で開始」は仮想画面を作るボタンではありません。

[仮想画面の公式仕様](https://github.com/Genymobile/scrcpy/blob/v3.3.1/doc/virtual_display.md)。

## 3. 毎回のコマンドを省略（Windows用PS1）

[Windowsセッションランチャー](../../tools/windows/README.md)の手順で、PS1とJSONをscrcpyのフォルダーへ配置します。

```powershell
.\start-stellashell.ps1
.\start-stellashell.ps1 -Device redmagic
```

サンプルJSONのIPを書き換えて使用します。このPS1はTCP接続用です。USBシリアルで使う場合は上の直接コマンドを使ってください。

## 日本語入力・終了・困ったとき

- UHIDではAndroid側のIMEが変換を担当します。Androidの物理キーボード配列（US/JIS）とIMEの言語切替を設定してください。切替キーはIMEごとに異なります。
- 日本語変換が必要なら、Stellaの「仮想画面のスクリーンキーボードを非表示」はOFF。画面上のキーボードだけを隠したい場合はAndroidの物理キーボード設定側を確認します。
- scrcpyを閉じるとその仮想画面を終了します。ShizukuやTCP 5555を停止する操作ではありません。
- 接続し直す前に旧scrcpyを終了してください。display IDが増えるだけでは仮想画面が残っているとは限りません。
- 本体がスリープすると操作できなくなる端末があります。Shizuku接続下でStellaのクイック設定「本体画面だけ消灯」を利用できるか確認してください。通常の電源ボタンによるスリープとは異なります。
- `unknown option`：scrcpyのバージョンを確認。`unauthorized`：スマホの認証。タイムアウト：IP・VPN・接続用ポートを確認。
- 外部画面へマウスが来ない：Shizuku接続とマウス診断を確認。物理USBモニターのマウス割当てと、scrcpyの入力転送は別経路です。
