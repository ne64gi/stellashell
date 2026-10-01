# StellaShell 0.9.0 — Phone Workspace（プレリリース）

## 変更点

- HOMEは横サイドバーへ統一。Desktopと共通のStart・検索・ピン留め・グループ・右クリックを維持。
- Phoneでは標準Androidの全画面起動が既定。明示した小窓だけをStellaで管理。
- Phone／Desktopのピン・ショートカット・ウィジェット・壁紙設定を分離。
- 壁紙にFill／Fitと焦点位置調整を追加。
- サイドバーの呼び出し高さ、HOME以外のアプリ上での表示ON／OFFを追加。
- 0.8.0 checkpointの画面消灯修正、通知／ウィジェットの本体遷移、下部ナビ、Windowsセッションツール等を含む。

詳しくは [Phone Workspace](PHONE-WORKSPACE.md) と [0.8.0](RELEASE-0.8.0.md) を参照。

## 検証と制限

- SOG06でHOME／共通Start検索、通常全画面起動、サイドバー継続、プロファイル分離、管理小窓だけの本体・仮想外部画面往復を確認。
- サイドバー高さとHOME限定の表示条件をinstrumentationで確認。初回のoverlay準備待ちで一度失敗し、HOME再開後の再試行でPASS。
- JVM 69件成功。lintはエラー0件（既存を含む警告あり）。
- 0.9変更のREDMAGIC回帰、物理USB抜き差し、長期HOME運用、壁紙の実指操作は未確認。全端末対応を保証しません。
- 本体と外部にShellを同時常駐する機能ではありません。普通に起動したPhoneアプリを勝手に外部へ移動しません。

開発用署名APKです。既存開発版と同じ署名で更新でき、標準HOMEは自動変更しません。安定版／v1ではありません。
