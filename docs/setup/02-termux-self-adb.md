# ② Termux + android-tools + self-ADB :5555（任意）

[手順の入口](README.md) · **通常のStellaShell導入には不要です。**
この手順のゴールはTermuxから自分自身へADB接続すること。Shizukuの起動やStellaShellの許可付与は別です。

## 1. Termuxとスクリプトを準備

[Termux公式のインストール案内](https://github.com/termux/termux-app#installation)から導入します。Termuxとプラグインは配布元・署名を揃えてください。
このリポジトリの [setup-self-adb.sh](../../tools/setup-self-adb.sh) と [adb5555](../../tools/adb5555) を端末のDownloadへ保存します。
Termuxで共有ストレージアクセスを設定し、ホームへコピーします。

```bash
termux-setup-storage
cp ~/storage/downloads/setup-self-adb.sh ~/
cp ~/storage/downloads/adb5555 ~/
```

Android設定で開発者向けオプション、USBデバッグ、ワイヤレスデバッグを有効にします。Android 11以上を想定しています。

## 2. 初回セットアップ

```bash
bash ~/setup-self-adb.sh
```

スクリプトは `pkg update` / `pkg upgrade` を実行し、`android-tools` と `nmap` を導入します。その後：

1. Androidのワイヤレスデバッグ→**ペア設定コードによるデバイスのペア設定**を開く。
2. スクリプトにその画面の**ペアリング用ポート**を入力。
3. `adb pair` に6桁コードを入力。分割画面などでペアリング画面を開いたままにすると操作しやすくなります。
4. 接続用ポートの自動探索を待つ。見つからない場合は、ワイヤレスデバッグの通常画面にある**IPアドレスとポート**のポートを入力。
5. `SELF-ADB READY` を確認。

**ペアリング用ポートと接続用ポートは別物**です。探索範囲は30000–50000だけなので、範囲外は手入力してください。

```bash
adb -s 127.0.0.1:5555 get-state
# device と出れば完了
```

## 3. 日常用コマンドを設置

```bash
mkdir -p ~/bin
cp ~/adb5555 ~/bin/adb5555
chmod +x ~/bin/adb5555
~/bin/adb5555
# 自動探索できない場合は現在の「接続用」ポートを指定
~/bin/adb5555 54321
```

日常は `adb5555` だけを使います。初回スクリプトを毎回実行する必要はありません。
5555が生きていればそのまま終了し、停止中ならペアリング済みのWireless ADBから `tcpip 5555` に切り替えます。

## 4. PCから接続する場合

PCでは `127.0.0.1` ではなく**スマホのIP**を使います。

```powershell
.\adb.exe connect 192.168.1.10:5555
.\adb.exe -s 192.168.1.10:5555 get-state
```

IPは例です。PCのADB鍵はTermuxとは別なので、スマホ側でPC接続の認証が必要になることがあります。
VPN経由なら双方の経路も必要です。ポート5555をインターネットへ公開する設定は不要です。

## 再起動・自動化・終了

- TCP 5555は永続設定ではありません。端末／adbd再起動やOEMの挙動で解除されます。
- `adb5555` はワイヤレスデバッグを勝手にONにしたり、ペアリングを省略したりはしません。
- Taskerを使うなら、Wi-Fi接続→約60秒待機→Termux:Taskerから日常用コマンド、を任意で設定。[ツール補足](../../tools/README.md)を参照。
- Shizukuを使う場合は[①の起動・許可手順](01-shizuku.md)へ。self-ADB成功だけではShizukuは起動していません。
- 接続を止めるなら下記。実行するとTCP接続も切れます。

```bash
adb -s 127.0.0.1:5555 usb
```

TCP ADBがLAN/VPN側でも待ち受けることがあります。信頼できるネットワークで使用してください。
`unauthorized` は認証、`offline` は接続状態、タイムアウトはIP・VPN・待ち受けポートを確認します。バッテリー制限等でTermuxが止められる場合は端末側の設定も確認してください。
