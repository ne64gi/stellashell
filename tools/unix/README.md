# Linux / macOS session launcher

Bash 3.2以上、`adb`、scrcpy（3.3.1相当の仮想画面オプション）を使用します。TermuxやTCP 5555は不要。StellaShellとShizukuのセットアップは別途必要です。

## 準備と起動

1. [scrcpy公式Linux手順](https://github.com/Genymobile/scrcpy/blob/v3.3.1/doc/linux.md) / [macOS手順](https://github.com/Genymobile/scrcpy/blob/v3.3.1/doc/macos.md) からscrcpyとADBを用意。
2. このリポジトリを取得し、次を実行します。

```bash
bash tools/unix/start-stellashell.sh
```

対話メニューでスマホの **開発者向けオプション → ワイヤレスデバッグ** をONにするよう案内します。

- **1：初回ペア設定** → スマホでペア設定コードの画面を開く → そのIP:ポートを入力 → adbに6桁コードを入力。
- **2：ペア設定済み** → ワイヤレスデバッグの親画面に表示される接続用IP:ポートを入力。
- **q：中止**。

ペア設定用と接続用のポートは別です。PCとスマホを同じWi-Fiへ接続するか、到達できるVPN等を使用してください。Shizukuのペア設定だけではPCは認証されません。[Android公式手順](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi)。

```bash
# 接続先が分かっていれば直接指定。失敗時は対話メニューへ。
bash tools/unix/start-stellashell.sh --address 192.168.1.10:37123

# 既存のself-ADB :5555も利用可能
bash tools/unix/start-stellashell.sh --address 192.168.1.10:5555 --windowed

# 再設定／非対話／画面調整
bash tools/unix/start-stellashell.sh --setup
bash tools/unix/start-stellashell.sh --address 192.168.1.10:37123 --non-interactive
bash tools/unix/start-stellashell.sh --resolution 2560x1440 --dpi 200

# PATH外の実行ファイル（空白を含むパスも可）
ADB=/path/to/adb SCRCPY=/path/to/scrcpy bash tools/unix/start-stellashell.sh
```

`--keyboard uhid|sdk`、`--ime local|fallback|hide` に対応。初期値は1920×1080 / 160 dpi、UHID、local、全画面です。UHID入力ではAndroidのIMEと物理キーボード配列を設定してください。

追加のJSONパーサーを不要にするため、シェル版はWindows用JSONを読みません。設定を残したい場合は上の起動コマンドを自分のスクリプトへ保存してください。ペア設定コードは保存しません。Windows専用の右Alt→Meta変換は同梱していません。

接続／状態確認は各12秒で打ち切ります。5555開放、Shizuku自動起動、ポートスキャン、既存セッション強制終了は行いません。再接続前には前のscrcpyを終了してください。`--no-vd-destroy-content` で切断時のTask破棄を避けますが、状態復元はアプリ依存です。

Linuxで模擬ADBを使った接続フローを検証。macOS実機での表示・入力は未検証です。

## Windows／CommandキーでStartを開く

scrcpyのショートカットは左Altだけへ設定します（`--shortcut-mod=lalt`）。[scrcpy公式仕様](https://github.com/Genymobile/scrcpy/blob/v3.3.1/doc/shortcuts.md)では、既定のMODに左Super（Windows／Command）も含まれるため、この指定でAndroidへ渡すMeta操作と分けます。左Altの貼り付け・全画面切替などは引き続き使えます。

対応するStellaShellを使用し、scrcpyにフォーカスがあるとき、Androidへ届いたWindows／Command単押しでStartを開閉します。Meta＋Spaceなどの組み合わせではStartを開きません。UHIDを推奨します。Android側の物理キーボード配列とIMEも設定してください。

Linuxのデスクトップ環境やmacOSが予約したキーはAndroidへ届かない場合があります。このスクリプトはOS全体のキーフックやショートカット設定を変更しません。別のPCアプリへ移ると、そのアプリとOSの通常のキー操作を使います。実Windows／Command入力・Linuxの各デスクトップ環境・macOSでの実機動作は未検証です。
