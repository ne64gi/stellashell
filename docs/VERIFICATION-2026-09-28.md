# SOG06 / SOG Desktop 0.2.0 検証

## 環境

- Sony SOG06（Xperia 1 IV）、Android 14 / API 34、64.2.D.2.248。
- ADB TCP <SONY_IP>:5555、scrcpy 3.3.1。
- 別アプリ `dev.fuyumori.sogdesktop`、versionCode 2。Dextop と本体ホームは保持。
- 本アプリの overlay と通知を許可。Shizuku binder は存在、本アプリの Shizuku 許可は未取得。
- desktop/freeform のグローバル設定はこの検証で変更していない。

## 確認済み

- コンテナで assembleDebug / lintDebug 成功。lint: 0 errors, 7 warnings。
- 単体テスト17件成功（表示先・コマンド制約と設定変更の復元処理）。
- 最終 APK を adb install -r で実機へインストール成功。
- scrcpy の public virtual display 25、再接続後26を検出。private capture display24と本体0は除外。
- 外部画面に DesktopActivity、下部 overlay バーとアプリメニューを作成。
- ADB の display 指定タップでメニューを開き、166件のアプリ一覧を表示。
- scrcpy の H264 録画を ffprobe / ffmpeg で確認。1280x720、約159.793秒。バーとメニューの録画フレームを保存。
- 録画途中の0-byteファイルを当初映像未受信と誤認したが、終了後の有効な録画により訂正。
- 画面25を破棄すると26で再接続できた。本体ホームは変更していない。

## 制限・残件

- 端末ロック後、display0 の NotificationShade が HIDE_NON_SYSTEM_OVERLAY_WINDOWS を要求し、display26 のバーにも mForceHideNonSystemOverlayWindow=true が適用された。Surface は作成済みでも shown=false / isVisible=false となる。本体ロック解除後の操作検証はユーザーの解除待ち。
- アプリメニューからの起動→同じ画面での起動完了、ピン留め、折り畳み、freeform の実機一連操作は未完了。実装済みであることと実機成功を区別する。
- adb input text の英字検索は日本語 IME に変換された。これは検索フィルターの成功確認として扱わない。
- 物理 USB モニタ、ウィンドウ移動／リサイズ、稼働タスク一覧は未検証／未実装。
- 録画の初回表示確認後に入れた最終 APK の差分は lint 用の限定的な touch listener 注釈／ヘルパー化。最終版も外部ホーム起動を確認。

## 証跡

`(local evidence archive; not included)/sog-desktop-taskbar/`

- `session.mkv`: 初回の実機 scrcpy 録画。
- `bar-recorded.png`: 同録画20秒付近のバー。
- `menu-recorded.png`: 同録画90秒付近のアプリメニュー。
- `desktop.png`, `menu.png`: ロック中の外部画面キャプチャ。後者もメニューは非表示であり、メニュー成功の証跡ではない。
- `capture-src/`: Android の captureDisplay を使う外部画面限定の読み取り用診断ヘルパー。

## 終了処理

検証用scrcpyプロセスだけ停止し、バーが残存しないことを確認。録画解析用の一時コンテナも停止。ソフトウェアH264の録画も1280x720 / 520.986秒で有効。電話はすでに自動でスリープ・ロックへ戻っていたため、追加の電源キー操作は行わなかった。

最終APK SHA-256: `9900d5364549a7afc6288c54ab871d77c5d08a6e532f946e8b976b4975112ce9`
