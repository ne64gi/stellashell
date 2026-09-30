# StellaShell ライセンス・コード来歴監査（2026-09-29）

> 後続の再実装・通知整備は [修正・再監査報告](LICENSE-REMEDIATION-2026-09-29.md) を参照。以下の分類・行番号は修正前の監査対象を記録したもので、現行コードの状態と混同しない。

## 追補：F-02の初稿作成記録を追加調査

初回git commit以前の、この開発セッションの実行記録を限定的に調査した。以下は2026-09-28のUTC時刻（日本時間は+9時間）。

| 時刻 | 確認できた事実 |
|---|---|
| 02:15:42–44 | Dextop `MirrorService.kt` の1860–1995行を読み、出力に `intField`、`taskConfiguration`、`activityTypeOf` の本体と `taskBoundsOf` の宣言が含まれた |
| 02:15:58–59 | AOSP Android 14の `IActivityTaskManager.aidl`、`WindowContainerTransaction.java`、`IWindowOrganizerController.aidl` を取得し、API宣言を調査。Dextopのrecents処理も再読 |
| 02:18:13 | 当時のpackage `dev.fuyumori.sogdesktop` に `TaskBackend.java` の初稿を作成。今回指摘したreflection helper群が既に含まれる |

**新たに確定したのは「Dextopのhelper本体を実際に読んだ後に対応helperを作成した」という来歴。** 単なるAPI名検索しかしていなかった、とは説明できない。一方、この時間的関係だけで、短いreflection処理が著作権上の翻案に当たるとまでは断定できない。AOSP APIも併読されており、F-02は分類3のまま維持する。独立実装確認済みへの格下げはしない。

該当実行記録の時刻・行番号・SHA-256をローカル `verification/license-audit-2026-09-29/f02-session-evidence.json` に保存した。無関係な会話や端末情報は報告書へ転載していない。コードは変更していない。

### 採用する作業順序

1. F-02の来歴・扱いを解決する。現時点では未解決。独立実装としての整理が必要なら、そのための変更は別途明示して扱い、過去の来歴まで消えたことにはしない。
2. F-01のApache attributionを正式化する。
3. THIRD_PARTY_NOTICESを修正する。
4. StellaShell本体のLICENSEを権利者の意思で決定する。**GPL-3.0-or-laterの選択はF-02対策とは分離し、まだ決定していない。**
5. READMEの不使用断言を実態に合わせる。
6. 再監査する。
7. v1候補commitを固定する。
8. そのcommitからAPKを作成する。

以下の本文は初回監査結果で、上の追加証拠と併読する。

## 結論

**「すべて独立実装」「Dextop/Taskbar のソース不使用」「scrcpy は外部ツールのみ」を、この監査で無条件に保証することはできない。**

- **分類4：`PrimaryScreenPower.sync()` の Android 14 用初期化ブロックは scrcpy の `DisplayControl` 初期化を改変・組み込みしたものと判定。** 未コミットの0.7作業ツリーに存在する。APIを1回呼ぶだけでなく、クラスローダー生成・system-server classpath・native libraryロードの特徴的な手順が対応する。
- **分類3：`TaskBackend` のタスク情報 reflection helper 群は Dextop 由来の可能性を要確認。** 初回コミットから存在し、公開済み版にも含まれる。Dextopの該当処理を参照した記録と、helper分割・取得順序の対応がある。一方、Androidのデータ構造上必要な処理でもあり、GPLコードの翻案と断定できる証拠まではない。
- **分類4：Gradle wrapper 一式は Taskbar 参照版と一致する共通ビルド資材。** Taskbar固有ランタイムの移植とは区別する。Apacheライセンス本文の一致も確認した。
- **StellaShell自身のルート `LICENSE` は存在しない。** 依存先のライセンスを同梱しても、StellaShell自身の利用許諾が定まったことにはならない。
- `THIRD_PARTY_NOTICES.md` と README の scrcpy 表記は、現作業ツリーについては不十分。Dextopについても分類3解消前の断定的な「source not incorporated」は監査済みの結論として扱えない。

本監査ではコード、LICENSE、NOTICE、READMEの修正・削除、commit、push、APK更新をしていない。追加したのは本報告書だけ。

## 1. 対象・方法・限界

### 対象版

| 対象 | 固定した版 | 状態 |
|---|---|---|
| StellaShell ローカルHEAD | `d8a4f3e86101b9ab648ffb213945d57d462ad936` | 0.6.1 checkpoint |
| StellaShell 作業ツリー | 上記＋監査開始時の変更、未追跡ファイル | 0.7.0 / versionCode 16。main Java 42ファイル |
| GitHub main | `3bfb0ebb62481681a89af99bce234d1464c0adcc` | `git ls-remote` で照合。ローカルHEADとは異なる |
| Dextop | `b3ecbbd04ba89ebf5774a609f299e16ca3486458` | THIRD_PARTY_NOTICES指定版、clean |
| Dextop Sony実験作業ツリー | `bfa8eaaa850f481862f04302d5911f8b92deddb7` ＋ローカル変更 | `dextop-sog06`。topology fallback等も比較範囲 |
| Taskbar | `e2a00b9a3f3adda069059abb9aebcb8b4e18954f` | THIRD_PARTY_NOTICES指定版、clean |
| scrcpy | `f01231dff8294fe2c99045a4f9a14b233a71bb86` / v3.3.1 | 実際の検証ツール・参照コードの版 |

### 実施内容

1. 既存のgit status、検証ノート、THIRD_PARTY_NOTICESを確認。既存変更を保持。
2. shallowではないStellaShellの全ローカルrefsから到達可能な **12コミット** を走査。184個の異なるpath/blob組をファイル内容の完全一致で比較。履歴上のファイル削除は検出しなかった。
3. Java/Kotlin/Dart/AIDL/XML/SVG/shell/Gradle等を、履歴の異なる版と現作業ツリーを含め **220個の比較単位** として照合。参照側の対象ソースは866ファイル。
4. コメント・空白を除いた30-token連続一致、識別子を一般化した65-token連続一致を補助検査に使用。後者はimport列などを過剰検出するため、結果をコピーの証明にしない。
5. reflection、task操作、起動、mouse routing、IME、widget host、screen capture/powerを重点的にメソッド比較。下記の全ファイル台帳で分類範囲と例外を指定。
6. API名・型・定数・短い定型句だけの一致と、実装の表現・構造の対応を区別。Java↔Kotlin翻訳はtoken一致が出ないため手動比較も実施。

**限界：** 初回 `d8f18a1` は既に0.5.3で、0.2〜0.5の個々の作成・編集履歴はgitにない。検証ノートはあるがclean-roomの証明ではない。全作者の原稿・全過去セッション・upstreamの全歴史版・公開APKバイナリの逆コンパイルまでの監査ではない。比較で一致がないことは独立創作の証明ではない。分類1/2はこの比較範囲での判定であり、法的な非侵害保証ではない。

### 分類の意味

1. **独立実装（監査範囲での暫定判断）**：対象upstreamの固有表現との対応が見つからず、Stella固有の状態・UI・データ設計として説明できる。
2. **アイデア/API利用方法のみ参考**：同じAndroid APIや機能を使うが、固有実装のコピーを裏付ける対応がない。
3. **構造的に強く由来している可能性があり要確認**：参照記録と構造の対応があるが、API制約で説明できる可能性が残る。コピー確定でもGPL適用確定でもない。
4. **コード／表現の直接コピー・改変**：一致、または対応する手順の改変組み込みが確認できる。適法な再利用・自作ヘルパーの再利用も含み、違反という意味ではない。

## 2. 分類3・4の詳細

### F-01 — scrcpyの初期化手順：分類4、確度 高

- Stella：[PrimaryScreenPower.java](../app/src/main/java/net/fuyumori/stellashell/PrimaryScreenPower.java) `sync()` **40–49行**、特に43–47行。
- upstream：[scrcpy DisplayControl.java 19–39行](https://github.com/Genymobile/scrcpy/blob/f01231dff8294fe2c99045a4f9a14b233a71bb86/server/src/main/java/com/genymobile/scrcpy/wrappers/DisplayControl.java#L19)、static initializer。
- 導入：**未コミット／未追跡の0.7ファイル**。12コミット中にこのファイルはなく、現在の公開mainにもない。

| 対応する処理 | scrcpy | StellaShell |
|---|---|---|
| クラスローダーfactory取得 | `ClassLoaderFactory` reflection | 同じ |
| factoryの引数型列 | String×3, ClassLoader, int, boolean, String | 同じ順序 |
| classpath | `Os.getenv("SYSTEMSERVERCLASSPATH")` | `System.getenv(...)`へ変更 |
| invoke引数 | classpath, null, null, system loader, 0, true, null | 同じ順序 |
| 対象クラス | `com.android.server.display.DisplayControl` | 同じ |
| native library | `Runtime.loadLibrary0(Class,String)`をaccessibleにして`android_servers`をload | 同じ手順 |
| キャッシュ／例外 | static initializer / CLASS / log | `tokenApi`遅延キャッシュ / 外側の例外復旧へ変更 |

APIシグネチャの30-token一致だけで判定したのではない。**通常のアプリAPIではないbootstrap全体の選択・順序が一致し、実装時にscrcpyの該当ファイルを参照した経緯、Stella側のscrcpyコメントもある。** そのため単なる分類2ではなく、改変した取り込みとして扱う。

`sync()`のdisplay0物理ID選択、external-only条件、Binder owner、watchdog、wake lock、`release()`はこのブロックと分ける。これらのStella固有セッション処理は分類1。`getPhysicalDisplayToken`／`setDisplayPowerMode`呼び出し自体は分類2で、比較箇所は [scrcpy SurfaceControl.java](https://github.com/Genymobile/scrcpy/blob/f01231dff8294fe2c99045a4f9a14b233a71bb86/server/src/main/java/com/genymobile/scrcpy/wrappers/SurfaceControl.java#L123)。ファイル全体がscrcpyのコピーという指摘ではない。

**対応候補（未実施）：** Apache-2.0に従って由来・変更を明示し、scrcpyの関連copyright表記を保持。単なる謝辞追加で済んだことにしない。

### F-02 — Dextopタスク情報helper群：分類3、確度 中（移植は未確定）

- Stella：[TaskBackend.java](../app/src/main/java/net/fuyumori/stellashell/TaskBackend.java) `field/number/configuration/mode/bounds/type` **42–49行**、`tasks` **61–64行**。
- upstream：[Dextop MirrorService.kt 1948–2010行](https://github.com/NarYuki/Dextop/blob/b3ecbbd04ba89ebf5774a609f299e16ca3486458/android/app/src/main/kotlin/moe/n4tsu/dextop/MirrorService.kt#L1948)：`resizeRecentsTaskThroughBinder`、`intField`、`taskConfiguration`、`activityTypeOf`、`taskBoundsOf`。
- [VERIFICATION-0.3.md](VERIFICATION-0.3.md) 冒頭に **このDextopメソッドを調べたことが明記**される。
- 導入：初回 `d8f18a1` に既に存在。後続のローカライズ・primary-mode対応後もhelperの骨格は変わらない。公開mainにも含まれる。

対応：task object → configuration → windowConfiguration → activity type / bounds のreflection、int field helper、display指定の4引数getTasksが一群としてまとまる。Stellaは共通`configuration()`に2段階を集約し、KotlinをJavaの短いhelperへ置き換えたようにも見える。

ただし反証材料も重要：このフィールド順序はAndroidの構造から要求される。Stellaはpublic field lookup、DextopはwindowConfigurationにdeclared-field/accessibilityを使用。getTasksの引数はStellaが`100,false,false,id`、Dextopが`64,true,false,displayId`。目的も通常アプリ管理とrecents補正で異なる。`mode()`は比較したDextop helper群にはない。

**したがって、GPLコードの翻訳と断定せず、参照記録とhelper集合の類似に基づく保守的な要確認分類とする。** 元の作成記録・Android一次資料を元にした実装過程が確認できれば分類2に下げられる可能性がある。対象外の`operate/applyBounds/launchProfile`までGPL由来と拡張しない。

**必要な確認：** 0.3実装時の編集履歴／生成記録から、helperを翻案したのか、APIの型だけ調べて新規に組んだのかを確かめる。確認前に「GPL非由来確定」として配布説明を確定させない。

### F-03 — Gradle wrapper：分類4、確度 高、Gradle共通資材

- Stella：`gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`。
- 比較：[Taskbar gradlew](https://github.com/farmerbb/Taskbar/blob/e2a00b9a3f3adda069059abb9aebcb8b4e18954f/gradlew)、[gradlew.bat](https://github.com/farmerbb/Taskbar/blob/e2a00b9a3f3adda069059abb9aebcb8b4e18954f/gradlew.bat)、[wrapper directory](https://github.com/farmerbb/Taskbar/tree/e2a00b9a3f3adda069059abb9aebcb8b4e18954f/gradle/wrapper)。
- 初回 `d8f18a1` の資材に完全一致がある。`dfdf2cb` でbatの改行を正規化。現在の両スクリプトは改行正規化後に全文一致。
- JAR SHA-256：`2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`（両者同じ）。

**これだけでTaskbar作者が書いたコードを移植したとは言えない。** Gradleが生成・配布する共通資材であり、Taskbarを経由したか同じGradle資材を別経路で取得したかはhashでは区別できない。スクリプトに既存のApache-2.0ヘッダーは保持されている。APKに入るTaskbar実装の発見ではない。ビルド資材の来歴として通知台帳に追加するのが適切。

`app/src/main/assets/licenses/Apache-2.0.txt` もTaskbar/LICENSEと完全一致（分類4）。これはライセンス本文の再利用であり、Taskbarのアプリコード取り込みの証拠ではない。

### F-04 — ローカルcaptureヘルパーの再利用：対象3件とは別の分類4

- Stella：[DesktopCapture.java](../app/src/main/java/net/fuyumori/stellashell/DesktopCapture.java) `write()` 9–24行。
- 直接の前身：ローカル `verification/sog-desktop-taskbar/capture-src/CaptureExternal.java` の `main()`。
- `createSyncCaptureListener → WindowManagerGlobal → captureDisplay → getBuffer → asBitmap → PNG` のreflection列を再利用し、出力をpathからParcelFileDescriptorに、エラー処理とrecycleを追加。
- 導入：`d8a4f3e`。公開main `3bfb0eb` には未収録。

Dextop／Taskbar／scrcpyの比較対象でこのcaptureDisplay列は検出しなかった。scrcpyの [video/ScreenCapture.java](https://github.com/Genymobile/scrcpy/blob/f01231dff8294fe2c99045a4f9a14b233a71bb86/server/src/main/java/com/genymobile/scrcpy/video/ScreenCapture.java) はSurface/動画エンコード向けの別実装。**自プロジェクト診断コードの再利用で、三者からのコピーを発見したという意味ではない。** ヘルパー自体はStellaのgit管理外で、初稿の出典はgitだけでは確定できない。

## 3. API参考と判断した主要箇所（分類2）

| Stella ファイル／メソッド | upstream比較箇所 | 判断理由 |
|---|---|---|
| `Launches.catalog/app/launch`、`TaskBackend.launchProfile`、`DesktopBridgeService.launch`、`Policy.launchCommand` | [Dextop AppCatalog.launch](https://github.com/NarYuki/Dextop/blob/b3ecbbd04ba89ebf5774a609f299e16ca3486458/android/app/src/main/kotlin/moe/n4tsu/dextop/AppCatalog.kt#L60)、[Taskbar U.getActivityOptions / getActivityOptionsBundle](https://github.com/farmerbb/Taskbar/blob/e2a00b9a3f3adda069059abb9aebcb8b4e18954f/app/src/main/java/com/farmerbb/taskbar/util/U.java#L939) | display指定・freeform=5・launch boundsは共通API。Stellaの主経路はShizukuサービスの限定amコマンド、既存task照合、WCT適用。TaskbarのFreeformHackHelperや旧stackId互換分岐、DextopのDesktopEnvironmentRegistry選択は移植されていない。 |
| `TaskBackend` constructor、`snapshot/change/apply/applyBounds/operate` | 上記Dextop `resizeRecentsTaskThroughBinder`、Taskbar U | DextopのrecentsをresizeTaskで補正する制御と異なり、StellaはtokenベースWCTとSystemUI transitionを使用。restoreBounds、JSON snapshot、caption/dock領域、root-task順の組み直しもStella固有。F-02 helperは別判定。 |
| `MouseRouting.probe/sync/association/awaitReleased/unroute/release` | [Dextop PhysicalInputRouter](https://github.com/NarYuki/Dextop/blob/b3ecbbd04ba89ebf5774a609f299e16ca3486458/android/app/src/main/kotlin/moe/n4tsu/dextop/PhysicalDeviceRouting.kt#L44) `discoverOperations/eligibleDevices/refresh/associate/restore` | descriptor→displayUniqueId、add/remove association、接続差分という方式は共通。DextopのpreferencesへのroutedKeys永続化・起動時clear、keyboard routing、独自仮想マウス名除外はない。StellaはBinder death lease、既存association保護、mouse限定、service直接再読・解除待ちという別の所有権管理。固有コードの翻訳を示す対応まではない。 |
| `VirtualKeyboardPolicy.probe/sync/release` | [scrcpy WindowManager.java 214–266行](https://github.com/Genymobile/scrcpy/blob/f01231dff8294fe2c99045a4f9a14b233a71bb86/server/src/main/java/com/genymobile/scrcpy/wrappers/WindowManager.java#L214)、[Dextop setDextopImeLocal](https://github.com/NarYuki/Dextop/blob/b3ecbbd04ba89ebf5774a609f299e16ca3486458/android/app/src/main/kotlin/moe/n4tsu/dextop/MirrorService.kt#L9543) | get/setDisplayImePolicy自体のAPI参考。Stella独自のvirtual-only条件・以前の値・display identity・Binder lease・他者変更時に復元しない処理。scrcpyの旧shouldShowIme互換分岐やDextopのoverlay IME focus制御はない。 |
| `DesktopWidgets.allocate/result/configure/rebuild/layout/start/stop` | [Taskbar DashboardController.addWidget](https://github.com/farmerbb/Taskbar/blob/e2a00b9a3f3adda069059abb9aebcb8b4e18954f/app/src/main/java/com/farmerbb/taskbar/ui/DashboardController.java#L544)、[DashboardActivity.addWidgetReceiver](https://github.com/farmerbb/Taskbar/blob/e2a00b9a3f3adda069059abb9aebcb8b4e18954f/app/src/main/java/com/farmerbb/taskbar/activity/DashboardActivity.java#L60) | allocate/bind/configure/createView/updateSizeはAndroid widget host標準フロー。TaskbarはセルID・BroadcastReceiver・DashboardActivityのpicker。Stellaはprovider一覧・pending ID・JSON自由配置・2host・Viewport。固有フローのコピー一致なし。 |
| `DockService.attachDock/update/enableHome`、`DesktopActivity.onCreate`、`Displays.available/target`、`AppMenu.open/render` | [Taskbar U.getDisplayContext](https://github.com/farmerbb/Taskbar/blob/e2a00b9a3f3adda069059abb9aebcb8b4e18954f/app/src/main/java/com/farmerbb/taskbar/util/U.java#L2135)、[Taskbar TaskbarController](https://github.com/farmerbb/Taskbar/blob/e2a00b9a3f3adda069059abb9aebcb8b4e18954f/app/src/main/java/com/farmerbb/taskbar/ui/TaskbarController.java)、[StartMenuController](https://github.com/farmerbb/Taskbar/blob/e2a00b9a3f3adda069059abb9aebcb8b4e18954f/app/src/main/java/com/farmerbb/taskbar/ui/StartMenuController.java) | 外部home＋overlay＋Startという構成の参考。Stellaは自前display対象選択、TaskSession、同一surface内folder、直接View構築。Taskbarのcontroller/helper/broadcast/XML layout体系はない。 |
| `SettingTransaction.apply`、`SetupActivity.apply` | [Dextop SamsungDesktopSettings.kt](https://github.com/NarYuki/Dextop/blob/b3ecbbd04ba89ebf5774a609f299e16ca3486458/android/app/src/main/kotlin/moe/n4tsu/dextop/SamsungDesktopSettings.kt)、Taskbar U | desktop/freeform設定と復旧という方針は参考。Stellaは2設定のStore抽象・両方rollback・suppressed exception。Samsung/OEM条件やDextop session設定管理の移植は見つからない。 |
| `res/values/styles.xml` のtranslucent/transparent設定 | Dextop `android/app/src/main/res/values/styles.xml` と `values-night/styles.xml`（上記固定版） | 32-token一致はAndroidのstyle item2つとタグ境界。短い定型の透過設定で、テーマ体系・リソース全体の移植を示さない。 |

## 4. 全main Javaファイルの分類台帳

表の「全メソッド」はそのファイルのconstructor、override、匿名listenerを含む。混在するファイルは例外を優先する。upstream対応なしは「証拠が見つからない」の意味で、世界中のコードからの独立性を証明したものではない。

| ファイル | 対象メソッド／範囲 | 分類と根拠 |
|---|---|---|
| AppChecklist | `create/normalize`、adapter・text listener | 1：一括選択用のStella organization UI。三者の固有処理対応なし |
| AppContextMenu | `show/customSize` | 1：ユーザー指定起動プロファイル項目。API実行はLaunches側 |
| AppLaunchProfile | constructor、`Plan/plan` | 1：ユーザー提案のenum・サイズ・位置モデル、独自clamp/cascade |
| AppMenu | 全メソッド、特に`open/render/grid/tile/appTile/groupTile/buildFolder/selectApps/groupTools/tools/closeFolder` | 2：Startという構成はTaskbar参考。folder内表示・検索focus修正・AppChecklist連携の具体実装は1 |
| AppOrganization | `prefs/groups/group/assign/hidden/hide/applySelection/addGroup/renameGroup` | 1：Stella用のgroup/hidden preferencesモデル |
| Appearance | `Config.json/read/typeface/foreground/linear/theme/load/surface/fonts` | 1：テーマJSON、Typeface、標準輝度計算、View traversal。識別子正規化検査の一致はimportなど定型構文で、Taskbarテーマ処理の移植を示さない |
| AppearanceActivity | 全メソッド、`colorInput/fields/readColors/preview/selectFace/applyFace` | 1：独自設定画面・フォント取込。三者由来の具体表現なし |
| Bridge | 全メソッド、`connect/call/screenOff/mouseDisplay/resetMouseRouting` | 1（対象三者との比較）：Shizuku APIを利用する自前AIDL client。Shizuku依存ライセンスは別項 |
| ChromeOcclusion | `visible/add` | 1：半開矩形の差分分割。対象三者の一致なし |
| DesktopActivity | lifecycle、`desktopAction/desktopMenu/wallpaper` | 2：外部home構成。独自shortcuts/widgets/wallpaperの組立て |
| DesktopBridgeService | `launch/applySettings/back` | 2：Android am/settings/inputの利用。`exec/read/write`と各AIDL dispatch/lifecycleは1。呼び出すPrimaryScreenPower/TaskBackend内の3/4とは区別 |
| DesktopCapture | `write` | 対象三者からの取り込みは未検出。ローカルヘルパーに対して4（F-04） |
| DesktopPlacement | `fit` | 1：小さな座標制限関数、固有表現一致なし |
| DesktopScreenshot | `take` | 1：MediaStore/FD/BridgeのStella接続処理 |
| DesktopShortcuts | constructor、`refresh/place/layoutAll/save/commit/drag/cancelMove/snap`、listener | 1：ユーザー指定自由配置・dp保存・任意snap。対象三者の固有表現一致なし |
| DesktopWallpaper | constructor、`choose/decode/result/reload/destroy` | 1：AtomicFile・世代ticket・非同期ImageDecoder。正規化一致はimport等の定型構文 |
| DesktopWidgets | 全メソッド | host標準APIフローは2（§3）。`choose/searchKey/save/options/renderSize/gesture`と自由配置/pending保存は1 |
| Displays | `available/ids/primary/primaryActive/allIds/target/require` | 2：DisplayManagerで選択、Dextop topologyは使用しない |
| DockService | 全メソッド | overlay taskbar構成は2。電池、通知、復帰、タスク差分、Stella各画面接続の具体実装は1 |
| ErrorText | `localize` | 1：Stellaエラー文とresourceの対応 |
| FontCollection | `count` | 1：TTC/OpenTypeヘッダーの範囲検証。対象三者の対応なし。フォント本体は同梱されない |
| HubActivity | 全メソッド、`widgetMenu/showTab/renderNotifications/openNotification` | 1：Stella独自時計panel、Android通知とwidgetsの組立て |
| Launches | `catalog/app/launch/home/settings/desktopAction` | 2（§3）。`prefs/recents/remember/pins/togglePin/desktop/toggleDesktop/shortcuts/problem`は1 |
| MouseRouting | 全メソッド | 2（§3）。F-02のようなhelper翻案疑義よりも、API経路と一般的な接続差分処理の共通性と判断 |
| Policy | `launchCommand/backCommand` | 2：Androidコマンド。constructor、`selectDisplay/requireTarget/component/setting/recent`は1：Stella限定条件 |
| PrimaryScreenPower | constructor、`sync/release` | **4：syncのbootstrap（F-01）**。token/power API自体2、それ以外のlease/復元1 |
| Profiles | 全メソッド、`key/requestedComponent/get/save/plan/begin/end/launched/observe/rememberSnapshot` | 1：Stellaのtask ID→component、JSON profile、bounds記録 |
| QuickSettingsActivity | 全メソッド、`open/label/update`、volume listener | 1：Android音量/Wi-Fi状態を表示するStella UI |
| SettingTransaction | `apply` | 2：設定復旧の考え方のみ。具体transaction実装1 |
| SetupActivity | 全メソッド、`startDesktop/apply/diagnostics/refresh` | 2：capability診断・設定という方針。個々のUI、Bridge連携、復元保存は1 |
| ShellApplication | `onCreate/onSharedPreferenceChanged`、ActivityLifecycleCallbacks | 1：Stellaフォント適用処理 |
| ShellNotifications | 全メソッド | 1：Android NotificationListenerServiceの標準実装＋Stella observer管理 |
| StartPins | `initialize/get/toggle` | 1：StellaのStart用pin migration/storage |
| TaskBackend | `field/number/configuration/mode/bounds/type/tasks` | **3：F-02**。constructor、`snapshot/row/launchProfile/change/apply/applyBounds/operate`は2。`component/display/eligible/requireTask/method/record/reason`は1（API定型処理を含む） |
| TaskSession | constructor、`Task`、`refresh/action/resize/focusForDrag/flushDrag/close`、accessor/poll/diagnostic | 1：Stella Bridgeのsnapshot、ドラッグ直列化、間隔調整。upstream固有実装の対応なし |
| Ui | 全メソッド | 1：Stellaカラー・View・drawableヘルパー |
| VirtualKeyboardPolicy | 全メソッド | 2（§3）。復元・owner管理の具体処理1 |
| WidgetGeometry | `mode/scale/resize` | 1：ユーザー要求のprovider resize/force/scale計算 |
| WidgetLaunchContext | constructor、`startIntentSender` | 2：Android RemoteViews/Context経路のAPI研究。三者ではなくAOSPの呼出経路を参考にしたもの。options差分mergeとdisplay選択は1 |
| WidgetViewport | constructor、`contentSize/onMeasure/onLayout` | 1：logical sizeでmeasureしtransform。Taskbarのcellサイズ直接設定とは異なる |
| WindowChrome | 全メソッド・内部クラス、`update/begin/end/relayout/clear`、`Frame.layout/part/gesture`、`CaptionButton.onDraw` | 1：Stella task boundsとocclusionを使う分割overlay。ユーザー要求から育てたchromeで、三者の固有表現対応なし |
| WindowGeometry | constructor、`area/clamp` | 1：caption/dockを避けるStella座標制限 |

### その他のファイル

- `IDesktopBridge.aidl`：分類1。Stella独自のsettings/task/mouse/IME/capture/powerインターフェース。三者のAIDLの一致なし。
- AndroidManifest、Gradle設定：分類2（標準Android/Shizuku構成）、wrapperはF-03。DextopのFlutter/plugin構成やTaskbarの複数flavor体系は取り込まれていない。
- `app/src/test`、`app/src/androidTest`：比較範囲に含め、固有の長い一致なし。Stellaのgeometry/policy/profile/widget検証（分類1）。テストがあること自体を独立創作の証明にはしない。
- drawable/mipmap XML、`docs/stellashell-icon.svg`、values/values-ja：対象三者の素材の完全一致はなし。Stellaロゴ・文言は分類1。stylesの定型一致は§3の分類2。画像の知覚的類似判定までは実施していない。
- `tools/setup-self-adb.sh`、`tools/adb5555`：ユーザー提示のスクリプトを整形・調整した来歴。三者のコード取り込みは未検出。対象三者との関係は1、ユーザー原稿からの改変という意味では4。独立した第三者OSSライセンスを推測付与しない。
- `README.md`、検証docs：三者へのリンク・名称・API説明は2。upstream文書全体のコピーは確認していない。謝辞は許諾文ではない。
- `Shizuku-API-MIT.txt`、`Apache-2.0.txt`：ライセンス本文の再掲は4（意図した再利用）。ライセンス本文の共通性をアプリ実装コピーと誤認しない。

## 5. 履歴上の所在

| 所在 | F-01 scrcpy bootstrap | F-02 Dextop helper疑義 | F-03 wrapper | F-04 capture helper |
|---|---|---|---|---|
| 初回 `d8f18a1` | なし | あり | あり | なし |
| 公開main `3bfb0eb` | なし | あり | あり | なし |
| ローカルHEAD `d8a4f3e` | なし | あり | あり | あり |
| 監査時0.7作業ツリー | あり | あり | あり | あり |

`TaskBackend`履歴：初回→`8e68c6d`（主にローカライズ）→`b17e15d`（primary mode）→今回未コミット変更。F-02の核は最初から存在する。

`MouseRouting/VirtualKeyboardPolicy`は`dabc038`にまとめて入っている。原型となった0.5.7の調査ノートはあるが逐次commitではない。

初回以降の全source版の30-token検査では、過去Javaファイルから対象upstreamへの長い一致は検出しなかった。これはF-02のような言語間の構造類似を否定しない。

## 6. LICENSE / NOTICE / README整合性

| 項目 | 判定 | 根拠・必要な扱い（変更は未実施） |
|---|---|---|
| StellaShell自身のLICENSE | **不足** | root LICENSEが全確認履歴と現ツリーにない。THIRD_PARTY_NOTICESや依存ライセンスだけでは独自部分のライセンスを定めていない。採用ライセンスを権利者が決める必要がある |
| Dextopのライセンス名 | 表記は正しい | READMEでGPL-3.0-or-laterを明示。root LICENSEはGPLv3本文。Stellaの表記と整合 |
| 「No Taskbar/Dextop source ...」 | **確認保留** | F-02を解決するまで監査保証として使えない。wrapper等の共通ビルド資材はAPK/runtimeと分けて説明すべき |
| scrcpyは外部開発ツールのみ | **現ツリーでは不十分** | F-01はアプリソース内に入る。scrcpyプログラム全体を同梱していないことと、ソースの一部を改変したことは両立する |
| Apache-2.0本文 | 存在する | assets/licenses/Apache-2.0.txtあり。ただし本文があるだけで個別のcopyright/変更通知まで満たすとは限らない |
| scrcpy attribution/変更通知 | **不足** | `also used by scrcpy`コメントはあるが、元ブロックを改変した旨の明確な通知ではない。scrcpy LICENSE/READMEのGenymobile・Romain Vimont表記をStellaの関連noticeに保持していない |
| Taskbar NOTICE | 条件付き | upstreamにはBraden FarmerとAOSPへのNOTICEがある。Taskbar固有のコード/資材を取り込むなら関連notice保持を検討。共通Gradle wrapper一致だけからTaskbar NOTICE全体の必須性を断定しない |
| scrcpy NOTICE | rootファイルなし | 固定したv3.3.1 rootにNOTICEはない。存在しないNOTICEを要求しない。LICENSE/READMEの関連copyrightは別に確認する |
| README Special Thanks | 謝辞として適切、許諾の代用ではない | 参照プロジェクト・非提携説明は妥当。ただしscrcpyについてソース利用もある現状を反映していない |
| Shizuku API/provider | 主な表記は整合 | build.gradleの13.1.5とnotice、MIT本文・RikkaW copyrightがある。対象三者とは別。推移的依存を含む完全SBOM／配布APK内の全notice検証ではない |
| AndroidX annotation / JUnit | 宣言と表記は整合 | compileOnly / testImplementation。実際の全バイナリ配布物までの監査ではない |
| ユーザー取込フォント／壁紙 | このrepoの同梱物ではない | 選択UIがあることと、フォント・壁紙の再配布は別。ユーザー端末上の素材をOSS配布資材と見なさない |

法的評価の前提となる条文：

- [Apache-2.0 §4](https://www.apache.org/licenses/LICENSE-2.0)：ライセンス本文、変更通知、関連権利表記、存在する場合のNOTICE保持を確認する。独立部分までApacheに変更する義務と混同しない。
- [Dextop LICENSE / GPLv3 §§4–6](https://github.com/NarYuki/Dextop/blob/b3ecbbd04ba89ebf5774a609f299e16ca3486458/LICENSE)：GPL対象の派生物に該当するコードを組み込んで配布する場合、謝辞だけでなく対象作品の許諾・対応ソース等の条件を検討する。APIのアイデアを参考にしただけで自動的にGPLになるとはしない。
- [Dextop READMEライセンス宣言](https://github.com/NarYuki/Dextop/blob/b3ecbbd04ba89ebf5774a609f299e16ca3486458/README.md#license)、[Taskbar LICENSE](https://github.com/farmerbb/Taskbar/blob/e2a00b9a3f3adda069059abb9aebcb8b4e18954f/LICENSE)、[Taskbar NOTICE](https://github.com/farmerbb/Taskbar/blob/e2a00b9a3f3adda069059abb9aebcb8b4e18954f/NOTICE)、[scrcpy LICENSE](https://github.com/Genymobile/scrcpy/blob/f01231dff8294fe2c99045a4f9a14b233a71bb86/LICENSE)。

これはコード来歴の技術監査である。F-02の短いAPI reflection表現が保護対象となるか、派生著作物に当たるかについて最終的な法律判断はしていない。

## 7. 次に行うべきこと（提案のみ）

1. F-02の初稿作成根拠を調べ、API参考なのか翻案なのかを解消する。確認できなければ「不明」のまま管理し、独立実装済みと断定しない。
2. F-01はApache-2.0の条件に沿った由来・copyright・変更通知を用意する。勝手にコードを消したり、変数名を変えて由来を消したことにしない。
3. Gradle等のビルド資材と、ランタイムの取り込みを分けたnoticeに整理する。
4. 上記の権利関係を踏まえてStellaShell本体のLICENSEを決定し、README/THIRD_PARTY_NOTICESを実態に合わせる。
5. 配布対象を決めた時点で、そのcommitとAPK、同梱notice、ソース提供先を対応づけて最終確認する。今回の監査は配布承認ではない。

## 8. 再現用証跡（ローカル、未公開）

`/home/fuyumori/android-dev/verification/license-audit-2026-09-29/`

- `scan.py` / `token-matches.json`：全履歴・現ソースの連続token比較。
- `structural.py` / `structural-matches.json`：識別子を一般化した補助比較。import等の偽陽性を含む。
- `exact.py` / `exact-matches.json`：履歴全ファイルの完全一致。
- `scope.json`：HEAD、対象commit、件数。
- `worktree-sha256.json`：監査時既存97ファイルのfingerprint（本報告書追加前）。

上記スクリプトは監査用で、AST解析や法的判断を自動化するものではない。依存版を固定したローカルreference checkoutと併用する。ビルドや実機操作は必要がないため実行していない。
