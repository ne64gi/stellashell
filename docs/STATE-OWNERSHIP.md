# Shellの状態と所有境界

設定・画面・タスクの状態は、Android Serviceや表示部品を正本にしない。表示部品は管理役の読み取りモデルと操作窓口を使う。

## 所有者

| 対象 | 正本・窓口 | 呼び出し側の責務 |
| --- | --- | --- |
| Dock / Taskbar / 表示形式 / Workspaceの構成・次回出力選好 | `ShellSettings` | 不変の設定snapshotを読む。型付きsetterで変更し、型付きChange通知を購読・解除する |
| 実行中・実際の選択出力・phone navigationの準備状態 | `ShellRuntime`の世代付きregistry | snapshotを読む。開始・停止・Start要求などのcommandを送る。ServiceやViewを取得しない |
| HOMEの可視状態 | `ShellRuntime.HomeVisibilityLease` | Activityごとのleaseを表示・非表示・closeする。複数Activityの可視状態を一つのbooleanで上書きしない |
| 選択出力の寿命と切替 | `ShellController` | Androidイベントを渡す。設定やタスクの内部状態を直接変更しない |
| 本体Dock / Taskbarの寿命・Startの振分け | `PhoneNavigationOwner` | 設定をreconcileする。生成・close・実readyの判定はownerへ任せる |
| 一つの出力のTaskbar / Dock / Start / caption / WorkArea監視 | `SelectedOutputSurface` | 出力の寿命内で生成・破棄する。別出力や本体navigationのViewを共有しない |
| 選択出力のtask snapshot・操作・Workspaceの所有identity / role / transfer | `TaskState` | 不変`TaskSnapshot`を読む。identity付きcommandを送る。内部`TaskSession`・`Workspace`を操作しない |

`DockService`はforeground通知とAndroid lifecycleの入口。設定、View、タスク一覧、staticなService実体を持たない。

## 保存値と実行状態

- `preferred_display`は次回の出力選好。`ShellSettings`が既存の`desktop`保存領域・キー・型を維持する。
- `workspace_display`はWorkspaceの保存済み移送先。`TaskState`が移送結果に応じて保存する。
- 実際に選択中の出力は`ShellRuntime.snapshot()`。`active_display`は互換用の診断cacheとしてのみ書き、動作判断に読まない。
- `enabled`は再起動時の復旧要求を維持する永続marker。実際にServiceが動いているかとは別で、`ShellRuntime`の窓口で扱う。
- pins / Start pins / wallpaper / shortcutsの本体・外部profile、通知、アイコン、外観、system display densityは各既存domainの所有者を維持する。全設定を一つの巨大managerへ統合しない。

DockとTaskbarは別の設定・pins・倍率を持つ。外部Taskbarの常設仕様、本体navigationが外部選択出力から独立する仕様は変更しない。

## 寿命と不変条件

- runtime registryはService/controllerを弱参照し、Service自身がcontrollerの寿命を所有する。終了したServiceをglobalな登録だけで保持しない。
- runtime bindingとoutput leaseには世代・実体照合がある。古いcloseや遅延応答は新しい出力を退役・更新できない。
- タスク一覧とstackは不変list。タスクの矩形は不変座標値で、Android `Rect`への変換は毎回独立した値を返す。
- task identityはIDだけでなくcomponentとの組。再利用IDに対して古いrole・bounds・drag情報を適用しない。
- Workspaceのrole・移送・rollback・recoveryはowner内で処理し、OS側の成功・identity・世代を確認する。
- 本体アクティブ一覧のfeedは独立した読み取りモデル。選択出力のWorkspace ownershipを別の正本として書き換えず、成功した操作の反映は`TaskState`へ渡す。
- OS側のpin intentとmouse/IME routing leaseはBridge backendの所有物。UIのWorkspace stateへ移さない。

## 検証入口

JVMの`ShellRuntimeRegistryTest`、`PhoneNavigationOwnerTest`、`TaskSnapshotTest`とownership fitness testが、世代・可視lease・生成/close・不変snapshot・consumerの境界を確認する。設定互換と実Android View/Bridgeの試験はAndroidTest側で分ける。

隔離fixtureでは`ShellSettings.Provider` / `TaskState.Provider`からnonce保存領域に対応したownerを注入し、派生display/window Contextでも同じownerを使う。productionのService stateへ反射でfixture状態を設定しない。テスト専用のView観測は`ShellFixtureAccess`へ集約する。

実装・build・端末導入・実機PASSは別の証跡で確認する。この文書自体を実機PASSの証明にはしない。
