# StellaShell 0.8.0 — development checkpoint

versionCode: 17 / Shizuku UserService version: 23。v1／安定版ではありません。
この記録はソースの区切りであり、GitHub公開・Release配布の完了を意味しません。

## 0.7.0からの主な変更

- ダッシュボード、前提条件の案内、接続中画面の管理と対応する仮想画面セッションの終了。
- 任意のホームアプリ入口、Compactのメイン／サブTask運用。標準ホームの自動変更はしません。
- アイコンパック・個別アイコン、ウィンドウの最前面固定（対応状況は端末依存）。
- ウィジェット編集パネルと右下resize、他パネルとの排他表示、タイトルバー遮蔽の修正。
- Widget/Notification Centerの切替を下部ナビへ変更。通知badge用の構造のみ用意し、未読管理は未実装。
- 通知の起動先を本体画面に指定。通知／ウィジェットの外部→本体送信後に案内を表示。
- 外部画面移行後の本体消灯を修正。タイトルバーは上の角だけ丸め、空白長押しでのウィジェット編集誤起動を廃止。
- Windowsランチャーに右Alt→Android MetaのADBショートカット転送と、Stellaの星ウィンドウアイコンを追加。

## 検証範囲と既知の制約

- 最終チェック成功：APK／androidTest APKビルド、JVM単体テスト65件（失敗0）、Android lint（エラー0、警告106件）。Windows補助ツールの構文／C#検証は変更のない先行結果を再利用。
- 開発中にSOG06 / Android 14でホーム・画面管理・ウィジェット・本体消灯等の個別fixtureを確認。NX809J / Android 16でも一部fixtureを確認していますが、このタグの全機能を両端末で再検証したものではありません。
- REDMAGICの初回ウィンドウ位置、全画面化するアプリ、マウス割当・物理USB切替・セッション復帰には未完了の検証があります。
- Android 11下限、Compactでの実指操作、OEM別の標準ホーム継続運用は未検証部分があります。
- Windows補助ツールはPowerShell 7で構文／埋め込みC#を確認。Windows実機とPowerShell 5.1でのキーフックは未検証です。右Altは完全なHID置換ではありません。
- 通知／ウィジェットの画面指定はPendingIntentへ渡すものです。送信後にprovider自身が起こす別の遷移やservice／broadcast経由の起動先までは保証しません。
- 開発用署名APKを使用。既存のLICENSE／NOTICE／第三者表記を維持し、今回のcheckpointを新たな包括的ライセンス監査完了とは扱いません。
