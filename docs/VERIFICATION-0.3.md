# 0.3 検証・API調査（2026-09-28）

## 経路

- Dextop `MirrorService.resizeRecentsTaskThroughBinder` は IActivityTaskManager.getTasks / resizeTask で実際のタスクを操作。Taskbar は ActivityOptions の launch bounds / windowing mode を使用。
- SOG Desktop は既存の Shizuku UserService に限定的な taskSnapshot / taskOperation を追加。
- 実機で getTasks(int,boolean,boolean,int)、WindowContainerTransaction.setBounds / setWindowingMode / reorder、IWindowOrganizerController.applyTransaction、IActivityTaskManager.removeTask を確認し、実操作も成功。
- topology API は不要。タスク token は読み取った RunningTaskInfo の token を使用し、固定の Binder transaction 番号は使わない。
- API存在プローブ、使用backend、要求／実際のbounds、失敗を診断画面へ保存。ログは直近30操作。
- mutation 前に表示先を再検証。本体0・private display・対象表示先に属さないタスク・シェル自身・標準タスク以外を拒否。閉じるは removeTask で、package force-stop ではない。
- 最大化も mode=5 の作業領域最大化。上32dpはタイトル、下60dpはバー用に確保。
- 最小化はタスクを背面へ移動し、タスクバーの復帰で前面へ戻す。show desktop は専用ホームを前面へ。

参照（コードのコピーはしていない）:

- [AOSP Android 14 IActivityTaskManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/core/java/android/app/IActivityTaskManager.aidl)
- [AOSP WindowContainerTransaction](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/core/java/android/window/WindowContainerTransaction.java)
- [Dextop MirrorService](https://github.com/NarYuki/Dextop/blob/b3ecbbd04ba89ebf5774a609f299e16ca3486458/android/app/src/main/kotlin/moe/n4tsu/dextop/MirrorService.kt)
- [Taskbar U.java](https://github.com/farmerbb/Taskbar/blob/e2a00b9a3f3adda069059abb9aebcb8b4e18954f/app/src/main/java/com/farmerbb/taskbar/util/U.java)
- [scrcpy virtual display](https://github.com/Genymobile/scrcpy/blob/v3.3.1/doc/virtual_display.md)

## Android下バー競合の原因

AOSP DisplayContent.supportsSystemDecorations は display flag / per-display setting に加えて forceDesktopMode() を OR する。端末の force desktop=1 が no-vd-system-decorations より優先された。

実機で NavigationBar30 が底48pxの入力を受け取り、SOGバーを最下部に置くだけではクリックが届かなかった。単に描画位置を上書きして解決とはしなかった。

変更直前の値1を verification/sog-desktop-0.3/force-desktop-before.txt に記録し、force desktop=0へ変更。freeform=1は維持。独立display31を作り直すと NavigationBar31 は作成されず、バーのクリックが通った。0.3設定UIも0/1の組み合わせに変更。本体HOMEは変更しない。

参照: [DisplayContent](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/services/core/java/com/android/server/wm/DisplayContent.java)

## 実機証跡

環境: Sony SOG06 / Android14 API34 / 64.2.D.2.248、scrcpy3.3.1、1280x720/160dpi。ユーザーの通常ミラーdisplay29と会話アプリdisplay0は保持し、検証用display30→31だけ操作。

`(local evidence archive; not included)/sog-desktop-0.3/`

- `gestures.json`: タイトルドラッグ、四辺、四隅の入力をADBで注入し、実際のActivity bounds変更を検証。10操作すべて成功。
- `actions.json`: サイズ復元、左右半分、最小化、タスクバー復帰、デスクトップ表示、終了。8操作すべて成功。最大化も別途1280×720の作業領域 Rect(0,32–1280,660) を確認。
- `runtime-diagnostics.txt`: 同じ処理のShizuku backendログ。
- `resized.png`: アプリ本文と分離したタイトルバー、実サイズ変更後の電卓とバー。
- `desktop.png`: 全アプリ一覧を撤去した通常画面。
- `session.mkv`, `session-shell.mkv`: 検証中のscrcpy映像。
- `ui-probe/`: 外部画面上の本アプリUIだけを対象とする検証ヘルパー。既存accessibilityを抑止しない接続フラグを使用。製品APKには含めない。
- `source-before.tgz`: 作業前の0.2ソースを保持。

Startの検索→アプリ起動、Start長押し→デスクトップ追加→ショートカット起動、ピン留め→解除、デスクトップから削除も実機確認。検証で追加した電卓のpin/shortcutは解除し、ユーザーの既存pinを保持。

## 分かった制約

設定アプリの画面設定を第2のfreeformタスクとして開いたところ、mForceHideNonSystemOverlayWindow=true でバー／chromeが非表示になった。Androidの戻る操作で元のアプリへ戻ると復帰。これはAPI失敗や描画不良ではなくoverlay保護。常駐通知にもデスクトップ復帰アクションを追加したが、protected screenの制約自体を解除してはいない。

すべてのアプリでの動作、物理USB表示、OSアップデート後のhidden API互換は未保証。追加chromeはアクティブなタスクだけで、背面ウィンドウのchromeを前面へ重ねない。

## 最終成果物

- versionName 0.3.0 / versionCode 3 を実機へ更新インストール。
- 最終ビルド成功、単体テスト22件成功（失敗0）、lint 0 errors / 13 warnings。追加警告は意図した hidden API reflection など。
- 最終APK SHA-256: `9fc477fdb6338aa0ccbc9cb011f04c2365a970d5cfeb40d72183576e2f38525a`
- 本体HOMEは `com.ss.launcher2/.MainActivity` のまま。
- 最終APKでStart、バーの折り畳み／復帰、タスク復帰、最大化／復元、閉じるを再確認（final-ui-smoke.json）。

検証用display31と操作／キャプチャヘルパーは終了・削除。既存scrcpyサーバーを保持し、外部画面切断後にSOG overlayが残存しないことを確認。最終状態はdesktop-final.png / final-state.json。既存pin保持、desktop shortcutは空。
