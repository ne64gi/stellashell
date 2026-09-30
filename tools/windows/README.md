# Windows session launcher

StellaShell 0.7向けの任意ツールです。Windows PowerShell 5.1 / PowerShell 7を想定。
本体のセットアップやShizuku起動、ADBペアリングを自動化するものではありません。

## 配置と起動

1. [scrcpy公式Windows配布](https://github.com/Genymobile/scrcpy/blob/master/doc/windows.md)を展開します。ここで使うオプションはscrcpy 3.3.1の仕様に合わせています。
2. このフォルダーの `start-stellashell.ps1` と `stellashell.example.json` を、展開した `scrcpy.exe` / `adb.exe` と同じ場所へコピーします。DLL・scrcpy-server等も含め、公式配布の一式を残してください。実行ファイルだけコピーしても動きません。
3. `stellashell.example.json` を `stellashell.json` にコピーし、各端末の `address` を変更します。サンプルIPはダミーです。
4. AndroidにStellaShellをインストールし、Shizukuの起動と権限付与、ネットワークADB接続を準備します。初回は端末側でADB認証を許可してください。
5. そのフォルダーでPowerShellを開きます。

```powershell
.\start-stellashell.ps1                 # default の端末
.\start-stellashell.ps1 -Device xperia
.\start-stellashell.ps1 -Device redmagic
```

実行ポリシーで止まる場合は、内容を確認したうえで今回のプロセスだけ許可できます（システム全体の変更は不要）。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-stellashell.ps1 -Device xperia
```

端末名、IP/ポート、解像度、DPIはJSONで変更します。実際の設定 `stellashell.json` はこのリポジトリではGit除外です。
同一端末で繰り返し起動すると別の仮想画面が作られるため、接続し直す前に前のscrcpyを終了してください。
このスクリプトは既存のscrcpyを強制終了せず、display IDの採番もリセットしません。

## 入力と終了

- `keyboard: "uhid"` はAndroidへ物理キーボードとして入力します。PCのIME変換結果を直接転送する設定ではありません。Android側のIME・物理キーボード配列を設定してください。
- `imePolicy: "local"` は仮想画面側でIMEを扱います。Android側IMEが必要ならStellaShellの「仮想画面のスクリーンキーボードを非表示」はOFFにします。`hide` は変換にも影響します。
- `systemDecorations: false` でAndroidの仮想画面用システム装飾を隠します。
- `fullscreen: true` はPC上のscrcpyを全画面にする設定です。
- scrcpy終了で仮想画面セッションが終わります。ADBサーバーやTCP 5555は停止しません。
- エラー時は終了コード1。scrcpy側の詳細ログも表示します。ポート5555の有効化やShizukuの再起動は行いません。

[仮想画面の公式仕様](https://github.com/Genymobile/scrcpy/blob/v3.3.1/doc/virtual_display.md)。
スクリプトとサンプルはGPL-3.0-or-later。外部バイナリは同梱しておらず、それぞれの配布物のライセンス・NOTICEを保持してください。

検証: Linux上のPowerShell 7で構文とモックによる引数・失敗経路を確認。Windows PowerShell 5.1およびWindows実機でのscrcpy起動は未確認です。

- ランチャーは `--no-vd-destroy-content` を指定します。仮想画面の切断時に
  アプリのTaskを破棄せず本体へ戻します。Activityの状態復元はアプリ依存です。
