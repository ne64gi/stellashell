# Windows session launcher

StellaShell向けの任意ツールです。Windows PowerShell 5.1 / PowerShell 7を想定。
ワイヤレスデバッグのペア設定／接続を対話式に案内します。本体側の開発者設定やShizuku起動は別に行います。5555は不要です。

## 配置と起動

1. [scrcpy公式Windows配布](https://github.com/Genymobile/scrcpy/blob/master/doc/windows.md)を展開します。ここで使うオプションはscrcpy 3.3.1の仕様に合わせています。
2. このフォルダーの `start-stellashell.ps1`、`stellashell.png` と `stellashell.example.json` を、展開した `scrcpy.exe` / `adb.exe` と同じ場所へコピーします。DLL・scrcpy-server等も含め、公式配布の一式を残してください。実行ファイルだけコピーしても動きません。
3. `stellashell.json` がなければ初回起動時にサンプルから作成します。`address` は空欄でOK。既存のJSON／5555接続もそのまま使えます。
4. AndroidにStellaShellをインストールし、Shizukuの起動と権限付与を済ませます。PCとの接続は下のメニューで案内します。
5. そのフォルダーでPowerShellを開きます。

```powershell
.\start-stellashell.ps1                 # default の端末
.\start-stellashell.ps1 -Device xperia
.\start-stellashell.ps1 -Device redmagic
```

## 接続メニュー

保存済みの接続先が利用可能なら、そのまま起動します。空欄／到達不可なら次を案内します。

1. スマホの **開発者向けオプション → ワイヤレスデバッグ** をON。
2. 初回はメニュー **1** → スマホの **ペア設定コードによるデバイスのペア設定** を開く。
3. そのダイアログのIP:ポートを入力し、adbが求める6桁コードを入力。
4. スマホのワイヤレスデバッグの親画面に戻り、**IPアドレスとポート** を入力。
5. 接続できたらscrcpyを開始。接続先の保存は任意（`y`）。コードはJSONへ保存しません。

**ペア設定ポートと接続用ポートは別です。** ペア設定済みならメニュー **2** から接続用ポートだけ入力します。ポートは再起動やON/OFFで変わる場合があり、保存先が古ければ再入力できます。ShizukuとPCのペア設定は別です。

```powershell
.\start-stellashell.ps1 -Setup           # 接続できても案内メニューを開く
.\start-stellashell.ps1 -NonInteractive  # 自動処理用。接続失敗なら質問せず終了
```

接続／状態確認は各12秒で打ち切り、選択端末だけを確認します。他のADBセッションを切断しません。`adb tcpip 5555`、Shizukuの自動起動、ポートスキャンは実行しません。通常はPCとスマホを同じWi-Fiに接続してください。VPN経由は端末の表示IPに到達できるネットワーク設定が必要です。

[Android公式のワイヤレスADB手順](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi) に沿った案内です。

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

## Windowsキー／右AltでStartを開く

既定で `scrcpy.windowsKeyAsMeta: true`、`scrcpy.rightAltAsMeta: true`。既存のJSONでも省略時は有効です。
このスクリプトが起動したscrcpyが手前にある間だけ、左右のWindowsキーと右Altの操作を取り込みます。

- **右Alt＋Space → Android Meta（Win／検索）＋Space**。IME側がこの組み合わせに対応していれば入力言語を切り替えられます。
- **左右のWindowsキー単独は、離した時にStella Startを開閉**します。
- Windowsキー＋Space・文字・矢印などはAndroidのMetaとの組み合わせへ送ります。組み合わせ操作の後にStartを開くことはありません。
- 右Alt＋文字・数字・矢印・ファンクションキー等もMetaとの組み合わせとして送ります。
- **右Alt単独は、離した時にStella Startを開閉**します。対応APKとこのPS1の両方を更新してください。
- 左Altはscrcpyのショートカット用に残します（`--shortcut-mod=lalt`）。[scrcpy公式仕様](https://github.com/Genymobile/scrcpy/blob/v3.3.1/doc/shortcuts.md)で既定MODに含まれる左Superを外し、AndroidのMeta操作と分けます。
- 別のPCアプリが手前にある間に始めたWindowsキー／右Altは変更しません。押している途中で別アプリへ移った場合もStartを開きません。scrcpy終了でフックも終了します。

WindowsへWinキーを合成する方式ではなく、ADBの `input keycombination` でAndroidへ送る補助機能です。
対応しないAndroidでは起動時に案内を出します。無効化するにはJSONの `scrcpy` 内に
`"rightAltAsMeta": false` を設定してください。WindowsキーをPC側で使う場合は `"windowsKeyAsMeta": false` にします。両方OFFならキーフックを起動しません。2つの設定は独立しています。

これは完全なHIDキー置換ではありません。Metaを押し続けながらのマウス操作やキーリピートには対応せず、
キーの組み合わせを1回ずつ送ります。USキーボード配列を想定し、AltGrを使う配列は対象外です。
ADBによる遅延があり、Androidでフォーカスのある画面へ送るため、本体側へフォーカスを移した場合にも注意してください。

変更後はPS1を差し替えてセッションを再起動します。Windows実機では次を確認してください。

1. scrcpy内の入力欄で右Alt＋Spaceを2回押し、IMEが往復すること。
2. 左Altの貼り付け操作が従来どおり使えること。
3. 左右のWindowsキー単押しでStartが1回開閉し、Windowsキー＋Space／文字／Shiftでは単押しの開閉が混ざらないこと。
4. Windowsキーや右Altを押したまま別アプリへ移ると、Startを開かないこと。
5. 別のWindowsアプリではWindowsキーと右Altが通常動作し、scrcpyを閉じた後も影響が残らないこと。

単押し・リピート・組み合わせ・修飾キー・フォーカス移動・設定ごとの入力判定はオフラインテスト `pwsh -NoProfile -File tools/windows/tests/meta-relay.ps1` で確認できます。テストはWindows APIのフック、ADB、scrcpyを起動しません。
Windows PowerShell 5.1、Windowsでの実キーフック・OS予約キー・Android IMEの組み合わせは実機未検証です。

## ウィンドウアイコン

同梱の `stellashell.png` をPS1と同じフォルダーに置くと、起動するscrcpyのウィンドウアイコンをStellaの星に変更します。
画像は本プロジェクトの `docs/stellashell-icon.svg` を256×256 PNGへ変換したものです。
[scrcpy 3.3.1のアイコン指定](https://github.com/Genymobile/scrcpy/blob/v3.3.1/app/src/icon.c)である
`SCRCPY_ICON_PATH` を起動中だけ設定し、終了時には元の値へ戻します。
PNGがない場合は警告して従来のアイコンで続行します。scrcpy.exe本体やExplorer上の実行ファイルアイコンは変更しません。
Windows側のピン留め済みショートカットのアイコンは別管理です。実機での表示は未確認です。

## 接続ウィザードの検証

PowerShell 7（Linux上、模擬adb/scrcpy）で既存接続、初回ペア設定の成功／失敗、古いポート、入力検証、非対話エラー、中止、強制セットアップを確認。ペア設定コードの実入力、Windows PowerShell 5.1、実端末との初回ペア設定は未検証です。

開発者向けのオフラインテスト：`python3 tools/tests/session-launchers.py --pwsh /path/to/pwsh`（リポジトリルートから）。実端末には接続しません。

Windowsキー／右Alt単押しはADBからStellaの起動中セッションへ開閉要求を送ります。一般アプリには許可しないDUMP権限付きの明示receiverを使い、シェル停止中に勝手に起動しません。組み合わせキーの送信経路は従来どおりです。
