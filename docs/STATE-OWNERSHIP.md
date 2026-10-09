# Shellの状態と所有境界

設定・画面・タスクの状態は、Android Serviceや表示部品を正本にしない。表示部品は管理役の読み取りモデルと操作窓口を使う。

## 所有者

| 対象 | 正本・窓口 | 呼び出し側の責務 |
| --- | --- | --- |
| 4系統のpins・共通recent・profile別desktop配置 | feature-launchの`LaunchItems` | 不変snapshotを読む。toggle/remember/remove-reference commandを使い、保存文字列を直接編集しない |
| app catalog・公開launcher解決 | `AppCatalog` / `PublicLauncher` | catalogの非blocking snapshot/worker loadとpackage無効化を使い、entryをViewへ変換する。描画中のiconはbounded worker/LRUで読む。実行後のrecentやShell状態はapp executorへ委ねる |
| 明示したアプリの外部全画面起動 | `ExternalAppLaunch` / `PublicLauncher` | メニューは接続先候補だけを保持し、起動時に再検証。Bridge無しで通常UIDから起動し、workspace・profile・ピンを変更しない。成功した起動要求のrecentだけを更新する |
| 外部アプリ専用モード | `ExclusiveDisplaySession` / `ExternalAppMode` / `ExternalAppActivity` | 本体の待機Activityが一時leaseを所有。ShellController/PhoneNavigationOwnerは通知で表示だけを抑止し、保存設定を変更しない。旧Activityの破棄で新セッションを解除しない。切断/終了/停止で解放し、process再生成でアプリを再起動しない |
| 起動route・起動順序 | coreの`AppLaunchDecision` / `SerialLaunchQueue` | 不変request/facts、scheduler・busy・actionを渡す。完了はそのjobのtokenのみで通知する |
| Android / Bridge起動とTaskStateへの配線 | `ShellLaunchExecutor` / `ShellLaunchCoordinator` | UIは`Launches`の互換窓口を使う。TaskState/profile保存の正本を複製しない |
| Dock / Taskbar / 表示形式 / Workspaceの構成・次回出力選好 | `ShellSettings` | 不変の設定snapshotを読む。型付きsetterで変更し、型付きChange通知を購読・解除する |
| 実行中・実際の選択出力・phone navigationの準備状態 | `ShellRuntime`の世代付きregistry | snapshotを読む。開始・停止・Start要求などのcommandを送る。ServiceやViewを取得しない |
| HOMEの可視状態 | `ShellRuntime.HomeVisibilityLease` | Activityごとのleaseを表示・非表示・closeする。複数Activityの可視状態を一つのbooleanで上書きしない |
| 選択出力の寿命と切替 | `ShellController` | Androidイベントを渡す。設定やタスクの内部状態を直接変更しない |
| AUTO出力決定の寿命 | coreの`AutoOutputReconciler` | 現在のsession・移送先・公開display候補を渡す。設定・timer・Android操作はcontroller側に置く |
| 本体Dock / Taskbarの寿命・Startの振分け | `PhoneNavigationOwner` | 設定をreconcileする。生成・close・実readyの判定はownerへ任せる |
| 一つの出力のTaskbar / Dock / Start / caption / WorkArea監視 | `SelectedOutputSurface` | 出力の寿命内で生成・破棄する。別出力や本体navigationのViewを共有しない |
| 選択出力のtask snapshot・操作・Workspaceの所有identity / role / transfer | `TaskState` | 不変`TaskSnapshot`を読む。identity付きcommandを送る。内部`TaskSession`・`Workspace`を操作しない |
| 外部Desktop背景のfocus保護と明示編集UI | `DesktopBackdrop` / `DesktopInteractionActivity` | 背景taskは前面へreorderしない。メニュー・入力・結果待ちは別の一時taskで保持し、終了時はそのtaskだけを閉じる。native focusabilityはBridgeのexact-token leaseが所有する |
| StartのWeb検索先 | `feature-search`の`SearchSettings` / `WebSearchSettings` | 不変snapshotと型付き保存・購読を使う。windowごとの`StartSearchSession`が明示操作だけをappの`WebSearchLauncher`へ渡す |
| アプリ別の次回起動設定と観測済みbounds | `core.launch.LaunchProfileOwner` | 不変snapshotを読み、項目別commandで更新する。Android保存は`LaunchProfilePreferencesStore`、JSON互換は`LaunchProfileCodec`が担当 |

`DockService`はforeground通知とAndroid lifecycleの入口。設定、View、タスク一覧、staticなService実体を持たない。

## 保存値と実行状態

- `preferred_display`は次回の出力選好。`ShellSettings`が既存の`desktop`保存領域・キー・型を維持する。
- `workspace_display`はWorkspaceの保存済み移送先。`TaskState`が移送結果に応じて保存する。
- 実際に選択中の出力は`ShellRuntime.snapshot()`。`active_display`は互換用の診断cacheとしてのみ書き、動作判断に読まない。
- `enabled`は再起動時の復旧要求を維持する永続marker。実際にServiceが動いているかとは別で、`ShellRuntime`の窓口で扱う。
- pins / Start pins / wallpaper / shortcutsの本体・外部profile、通知、アイコン、外観、system display densityは各既存domainの所有者を維持する。全設定を一つの巨大managerへ統合しない。

DockとTaskbarは別の設定・pins・倍率を持つ。外部Taskbarの常設仕様、本体navigationが外部選択出力から独立する仕様は変更しない。

Web検索は独立した`web_search`保存領域で、本体／外部共通・初期OFF。検索語や履歴は永続化せず、入力中の通信も行わない。windowごとの`StartSearchSession`が検索先の購読とnavigatorを所有し、closeで解除・解放する。Viewはapp側のメニューが所有し、古いactionは新しいwindowへ操作を送れない。検索を開く際はAndroidのURL解決とdisplay指定を使い、Bridgeやtask identityの窓口は変更しない。

起動profileの表示は不変snapshot。画面で読んだprofile全体を書き戻さず、変更する項目だけを最新の保存値へmergeする。bounds観測とモード・サイズ・位置の設定が交差しても別項目を失わない。`Profiles.get`のlegacy planner値は毎回detached copyで、保存の正本にはしない。`launch_profiles`のversion 1 JSON・17項目・alias優先順・absolute座標と既定freeformは維持する。adapter全体をstatic cacheで保持しない。

検索と検索設定の画面検証は`Displays.requireUiTarget`を使う。本体の通常操作はDesktopの稼働・選択モードに依存しない。切断・不明・privateな出力は拒否し、本体へ自動転送しない。タスク操作用`Displays.require`のworkspace制約は別に維持する。

起動queueは既存のprocess-wide main-loop順序を維持する。waiting/activeを含むpending判定、先頭jobのbusy待ち、RuntimeException後の解放を一つのownerが持つ。同期完了は反復drainで処理し、重複・遅延したcompletionは次jobを解放しない。通常本体起動はworkspaceの選択・Bridge・profile plannerを必要としない。保存profileからのdesktop/freeform計画、task role、session barrier、HOME/PiPの操作契約は従来のまま。

## 寿命と不変条件

- runtime registryはService/controllerを弱参照し、Service自身がcontrollerの寿命を所有する。終了したServiceをglobalな登録だけで保持しない。
- runtime bindingとoutput leaseには世代・実体照合がある。古いcloseや遅延応答は新しい出力を退役・更新できない。
- タスク一覧とstackは不変list。タスクの矩形は不変座標値で、Android `Rect`への変換は毎回独立した値を返す。
- task identityはIDだけでなくcomponentとの組。再利用IDに対して古いrole・bounds・drag情報を適用しない。
- snapshot応答待ちの間にresizeが自身のWorkspace commandを取得した場合、そのresizeをbusy待ちより先にdispatchする。成功・対象消失・error・closeのどの経路でも自身のticketを解放し、後続commandと最新snapshot取得を妨げない。`TaskSession.Backend`は出力単位のadapterで、試験時もprocess-wide Bridgeを差し替えない。
- Workspaceのrole・移送・rollback・recoveryはowner内で処理し、OS側の成功・identity・世代を確認する。
- Workspaceがbusyな間の出力切替は`TaskState.whenIdle`の一度だけの完了通知で再開する。FIFO commandが終了するまで通知せず、取消・resetで待機を解除する。移送元はqueueへ登録した時点ではなく実行時の保存先から解決する。
- HDMIのAUTO判断は開始・設定変更・接続・切断処理完了のイベントで再確認する。常時pollは持たず、切替中の手動指定を優先する。TaskStateのrouting通知とShellRuntimeの寿命通知で古い判断を無効化し、UIが保存先キーを直接監視して別の正本を作らない。
- 本体アクティブ一覧のfeedは独立した読み取りモデル。選択出力のWorkspace ownershipを別の正本として書き換えず、成功した操作の反映は`TaskState`へ渡す。
- OS側のpin intentとmouse/IME routing leaseはBridge backendの所有物。UIのWorkspace stateへ移さない。
- SOG06 / Android 14の外部バー生成設定は`ExternalDisplayPolicy`の一時lease。セッションの要求はapp、global値・復元と実画面の世代確認はplatformが所有する。下部バーの抑止に成功したかと、マウスの表示先を更新できたかは別の結果として扱い、バー抑止の失敗を理由にマウスの復旧を省略しない。画面の選択先をこの互換処理から変更せず、選択済みの実外部画面だけを更新する。

## 検証入口

JVMの`ShellRuntimeRegistryTest`、`PhoneNavigationOwnerTest`、`TaskSnapshotTest`とownership fitness testが、世代・可視lease・生成/close・不変snapshot・consumerの境界を確認する。設定互換と実Android View/Bridgeの試験はAndroidTest側で分ける。

隔離fixtureでは`ShellSettings.Provider` / `TaskState.Provider`からnonce保存領域に対応したownerを注入し、派生display/window Contextでも同じownerを使う。productionのService stateへ反射でfixture状態を設定しない。テスト専用のView観測は`ShellFixtureAccess`へ集約する。

依存方向と新機能の配置は[モジュール構成](MODULES.md)を参照。coreのAndroid非依存、feature/platformのapp逆依存禁止、保存ownerと項目別commandは`ModuleArchitectureFitnessTest`と各moduleのcompileで固定する。

実装・build・端末導入・実機PASSは別の証跡で確認する。この文書自体を実機PASSの証明にはしない。

Start表示設定は`StartMenuSettings`が実menu displayId（0=本体/その他=外部）を明示してhiddenとStart pinsを管理する。旧hiddenを両profileの初期値として保存し、変更行だけ最新値へmergeする。グループ定義と所属は共通、Dock/Taskbarのpinsは別のまま。

## 任意位置Dock・復旧用HOME

- `ShellSettings.PhoneSide.GESTURE` が選択を所有。設定画面は明示順（両サイド/右/左/ジェスチャー）を使い、旧保存値を移行しない。
- `PhoneSidebar` が一時アンカー・向きと `DockGestureClient` の購読寿命を所有。画面/Bridge/入力機器イベントでだけ購読を照合し、幾何が同じDisplay通知で作り直さない。
- `DockGestureMonitor` は選別済み内蔵direct touchのブロッキングreaderとBinder leaseを所有。解除/死亡で子processを破棄。キーボード記録・入力grab/注入・周期取得・wake lockなし。アプリへ渡すのは可用性と成立した動作のみ。
- core `TouchFrames` / `CornerDockGesture` / `FloatingDockPlacement` はフレームと認識・配置を検証。練習Viewとglobal画面の開始可能領域は区別し、曲がり角から横移動を測る。任意位置Dockは縦列＋上下scrollとし、WidgetはDockから横へ引き出し、追従開始位置は浮動Dock自身の端を基準にする。複数指/ドロップ/回転前座標/直線スクロールを採用しない。
- `HomeRecovery` はHOMEの短時間カウントのみ、`HomeRecoveryEntry` はAndroid Intentの分類のみ。`HomeActivity` がOS HOMEを受けて設定を開く。DockやBridgeの生存を前提にしない。
