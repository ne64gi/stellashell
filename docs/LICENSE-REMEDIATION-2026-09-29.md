# ライセンス指摘の修正・再監査（2026-09-29）

## 対応範囲

オーナーから「scrcpyは正式に明記して利用し、それ以外の問題箇所は再実装する」と指示を受け、初回監査の対象箇所を修正した。前の「疑わしいコードを勝手に書き換えない」という制約に対して、今回は明示的に再実装が依頼された範囲である。

リポジトリの一時非公開についてはオーナーの告知文をREADMEへ追加した。こちらからGitHubのvisibility変更、削除、履歴改変、push、release操作は行っていない。「GPL違反が確定した」とは告知していない。

## 指摘ごとの状態

| 指摘 | 現行実装への対応 | 残る範囲 |
|---|---|---|
| F-01 scrcpy bootstrap（分類4） | 継続利用。PrimaryScreenPowerに上流commit、copyright、Apache-2.0、変更内容を記載。上流LICENSE全文と独自の取り込み通知をAPK assetsへ追加 | コピーを独立実装と呼び替えてはいない。本体全体のライセンス選定とは別 |
| F-02 Dextop helper類似（分類3） | 指摘された汎用reflection helper群を撤去。FrameworkTaskAccessの固定APIスキーマ＋型付きsnapshotに置換。TaskBackendの利用箇所も移行 | 過去版の分類3と来歴は残す。clean-room証明や歴史上の法的評価を解決したものではない |
| F-03 Gradle共通資材（分類4） | ビルド資材として通知に記録。既存copyright/Apacheヘッダー保持。batに変更通知追加。wrapper JARのMETA-INF/LICENSE存在も確認 | 共通資材のhashだけで入手経路やTaskbar固有コード取り込みを断定しない |
| F-04 診断captureヘルパー再利用 | 対象三者からの取り込みを示す追加証拠はなく、変更しない | git外の初稿来歴という初回監査の限界は維持 |
| 不使用の断言 | THIRD_PARTY_NOTICESを全面整理。READMEの「独立した」の表現を外し、scrcpyのソース利用と過去版の調査を区別 | 「全履歴でGPL非由来が証明された」とは書かない |
| 本体LICENSE | 未決定のまま明示 | オーナーによるライセンス選択が必要。GPLをF-02対策として自動採用しない |

## F-02の再実装内容と新しい分類

[API根拠と設計記録](TASK-API-PROVENANCE.md)にAndroid 11/14/16の版を固定した一次資料を記録した。

- `TaskBackend.field/number/configuration/mode/bounds/type/component`を削除。
- `TaskBackend.tasks`は表示先検証と新境界への委譲のみ。任意オブジェクトを各操作でreflectionする構造をやめた。
- `FrameworkTaskAccess.Entry`はtask identity、component、user/display、mode/type、bounds、token、visibility/focusを明示する。
- SDK公開フィールドはTaskInfoから直接読む。hidden APIのschemaはTaskInfo/Configurationの固定宣言から解決する。
- mode/typeはTaskInfoのgetterを優先し、それが存在しない旧frameworkではWindowConfiguration APIに限定したfallback。Dextop helperをfallbackとして残していない。
- 矩形とroot child配列をコピーし、受け取ったframework objectの変更と切り離す。
- root hierarchyは別の任意機能として扱い、取得失敗時の既存のstackOrder=false処理を保持。
- WCT操作、launch profile、ユーザー／表示先検査、PENSUM等のfullscreenからfreeformへの復帰変更は保持。
- 次回APK入替時に古いShizuku UserServiceが残らないよう、内部サービスversionを15から16へ変更。

**現在の新境界の分類：API利用部分は2、snapshotモデル・変換境界・接続部分は1（監査範囲での判断）。** 元の疑義helper群が残っていないことを確認した。同じAndroid APIを使う以上、メソッド名・引数型や必須のデータアクセスは共通する。識別子を変えただけで白としたのではない。

これは同じ開発コンテキストでの再実装であり、clean-roomとは呼ばない。過去の公開mainにあったF-02は分類3のままで、新コードへの評価を過去へ遡及しない。

## 検証

- `testDebugUnitTest`：47件、失敗0・エラー0。
- `lintDebug`：0 errors、49 warnings。新境界のhidden API reflectionを含む互換性警告等が残る。警告を無効化して合格にしてはいない。
- `assembleDebug`、`assembleDebugAndroidTest`成功。これは作業ツリーの検証用ビルドであり、固定commit由来のv1配布物ではない。
- SOG06 / Android 14のshell-UIDで `FrameworkTaskProbe` を実行し成功。
  - 実frameworkのRunningTaskInfoを使ったidentity、mode/type、focus/visibilityの変換。
  - baseActivity優先、topActivity fallback、component欠落の保持。
  - boundsとroot child配列を元オブジェクトの変更から分離。
  - 本体display 0：tasks=5 / stack=8。
  - 外部display 17：tasks=3 / stack=5。
  - primary mode無効時にdisplay 0を拒否。
- このprobeは読み取りのみ。アプリ起動、window移動・resize・close、端末の消灯状態変更はしていない。インストール済みStellaShellも入れ替えていない。転送したprobe用APK2つは実行後に削除した。
- APK ZIP内の `scrcpy-Apache-2.0.txt`、`scrcpy-NOTICE.txt`、`Shizuku-API-MIT.txt` がソースassetsと一致することを確認。

新backendでのUI操作一巡、REDMAGIC実機、Android 11のfallback分岐実行は今回未確認。列挙とdecodeの変更に対する実機検証であり、全端末・全機能の保証ではない。

## 再監査

初回と同じ固定upstream（Dextop、Sony実験tree、Taskbar、scrcpy v3.3.1）で全履歴と作業ツリーのtoken比較を再実行した。222比較単位、upstreamソース866ファイル。

- 新しいFrameworkTaskAccessの検出一致はTaskbarとの31-tokenのimport列。実行処理のコピーを示す一致ではない。
- TaskBackendに旧helper群は存在せず、新境界の実行処理に新たな分類3/4の取り込みは検出しなかった。新コードのAPI宣言はAOSPの型と照合した。
- scrcpyのbootstrap一致は意図的に残っており、分類4として通知・ライセンスを対応づけた。
- Gradle資材、ライセンス本文、style設定の定型一致は初回結果と同様。改変通知の追加自体は新しいコード由来問題ではない。
- 初回監査記録は消していない。現在の修正記録と区別した。

機械比較だけで著作権上の派生性を判定していない。この再監査は**今回の指摘への対応確認**であり、推移依存を含む完全SBOM、過去配布物すべての法的クリアランス、公開許可を意味しない。

## 再現・証跡

ローカル `verification/license-remediation-2026-09-29/` に以下を保存：

- `before/TaskBackend.java`：今回変更前の作業ツリー版。既存0.7変更を含む。
- AOSP API資料と `aosp-contract-sha256.json`。
- `build.log`、`probe-build.log`、`final-build.log`。
- `device-probe.log`。
- `re-audit/scan.py`、`token-matches.json`、`scope.json`、`scan.log`。
- 最終ビルドのAPK・ソースfingerprint確認結果（ローカルmanifest）。

shell probeの再現方法（実行先は権限のある検証端末）：debug APKとandroidTest APKを一時領域に置き、両者をCLASSPATHにして `app_process /system/bin net.fuyumori.stellashell.FrameworkTaskProbe` を実行する。通常アプリUIDのinstrumentationとは権限が異なるので、通常テストランナーからは呼ばない。

## まだ実施していないこと

本体LICENSEの決定、v1候補commit固定、そのcommitからの配布APK作成、公開再開。現在のdebug APKをv1と呼んだり、過去版の疑義が解決済みと説明したりはしない。

## 後続決定：0.7の本体ライセンスとWindowsヘルパー

ユーザーがGPL-3.0-or-laterを明示選択。本体LICENSE（GPLv3全文）、NOTICE（or-later指定と第三者区分）、APK用ライセンスassetsを追加しREADMEとTHIRD_PARTY_NOTICESを更新。過去F-02の判定は変更しない。上の「未決定」は調査時点の記録。v1への昇格・commit・公開再開は行っていない。

Windows用PS1とJSONサンプルをtools/windowsへ追加。PowerShell 7コンテナーで、既定端末・指定端末・任意フラグOFF・未登録端末・ADB認証待ち・scrcpy異常終了・文字列boolean拒否の7経路をモック検証。Windows実機／PowerShell 5.1は未確認。外部バイナリは同梱しない。新規GPL assetsを含むAPKの再ビルド・端末入替は未実施。

後続：通知⚙の変更を明示承認後、GPL assetsを含む0.7 APKをビルド・内容照合し、SOG06へ上書きインストール成功。詳細はVERIFICATION-0.7.0.md。固定commitからの公開リリースではない。
