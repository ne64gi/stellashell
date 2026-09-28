# SOG Desktop 0.4.0 検証

## 成果物

- versionCode 4 / versionName 0.4.0、SOG06 / Android 14 に更新インストール済み。
- コンテナで testDebugUnitTest / lintDebug / assembleDebug 成功。
- 単体テスト28件成功、lint 0 errors / 15 warnings。
- APK SHA-256: `a25b7d5d7c81b1eee06fd3e77b5cca92c477f0def78e4b19d7d970dc115cfb82`
- 証跡: `(local evidence archive; not included)/sog-desktop-profiles/`

## Android ウィジェット

標準 AppWidgetHost を使用。Android の個別作成許可と提供アプリの設定画面を経由し、「常に許可」は有効にしていない。

- Digital Clock Widget 2.0 を display37（1600×900/160）へ追加。提供元の設定画面も外部画面で完了。
- 時計の描画と時刻更新、編集モードの移動・リサイズを確認。
- 保存値: id=9、x=419、y=283、width=424、height=275（dp）。
- APK 更新後、さらに display38（1280×720/160）への再接続後も同じID・配置を復元。`widget-reconnected.png` / `.xml`。
- 検証用 Chrome Dino の ID=10 を追加・削除。その後の追加キャンセルも確認。最終的な SOG host の割り当ては時計1個のみ。`widgets-after-remove.txt` / `widgets-after-cancel.txt`。
- 元のホームのウィジェットIDを保持。既存ホームの host は操作しない。
- 時計はデモとして配置を残した。検証用の別ウィジェットとデスクトップの電卓ショートカットは削除。

Chrome Dino は今回、追加した枠の中身が空白だった。すべての提供元との互換性を確認したという意味ではない。提供元の設定画面の下部ボタンがバーに隠れた場合はバーを折り畳んで操作した。

## 起動プロファイル

- Start の長押し、デスクトップとタスクバーの context-click アクションで共通メニューを確認。マウスの物理ボタン入力そのものは未検証。
- 電卓を800×600で起動し、通常の再オープンが同じtaskをサイズ変更せず再利用することをbackend probeで確認。
- 最大化: 実際のbounds `[0,32,1600,840]`。
- カスタム900×700の入力と保存、実際の起動boundsを確認。
- ウィンドウを移動して閉じ、「前回の状態」で再起動。前後とも `[502,96,1402,796]`。`profile-before-restore.xml` / `profile-after-restore.xml`。
- フルスクリーン: mode=1、bounds `[0,0,1600,900]` の起動ログと保存状態を確認。長時間の操作安定性試験ではない。
- 電卓の「新しいウィンドウ」は Android の singleTask により既存タスクへ統合。backend が created=false と報告。別ウィンドウ作成に成功したとは扱わない。
- display38 では900×700指定が900×628へ補正され、top=32でタスクバー領域を残した。切断後に保存データでも確認。

検証ツールで検索欄の「電卓」と同名アイコンを取り違えた失敗があり、検索語を calculator に変更して再確認した。画面保護・非表示の状態でも操作対象を見つけられないことがあった。失敗した操作列を成功証跡には含めていない。

## 制限・後片付け

- 本体HOMEは `com.ss.launcher2/.MainActivity` のまま。
- 本体画面／タブレット用モードは未実装。display0/privateの操作禁止を維持。
- ウィジェット内のPendingIntentは提供元の動作に従い、起動プロファイルを通るとは限らない。
- 物理USBモニタ、すべてのウィジェット・アプリ、複数モニタ同時は未検証。
- ロック画面・保護画面ではoverlayが隠れる既存の制約を維持。
- セッション切断後、検証用scrcpyプロセスが終了済みであることとADB接続・インストール済みバージョン・配置データの保持を確認。
- 検証用ADBヘルパーを削除。ソースのコミット／公開はしていない。

## 参照

[Android公式: App widget host](https://developer.android.com/develop/ui/views/appwidgets/host)
