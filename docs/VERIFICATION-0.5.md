# SOG Desktop 0.5.0

## 成果物と範囲

- 自由配置ショートカット、位置保存、任意の24dp吸着、解像度変更時の表示補正。
- 非操作時は透明なリサイズ入力領域。ホバー／ドラッグ時だけ1dpの線を描画。
- タイトルバー操作ボタンをベクター描画へ変更。既存のwindow操作・起動プロファイル・widget hostは継続。
- versionCode 5 / versionName 0.5.0、Sony SOG06へ更新インストール。
- APK SHA-256: `4d142c48639d7d5e0c77b56ef7f9639bac396f6832b98d701f34630116a615c9`
- testDebugUnitTest / lintDebug / assembleDebug 成功。32 tests, 0 failures, 0 errors。lint 0 errors / 15 warnings。
- Redmagic `<REDMAGIC_IP>:5555` へは接続・操作していない。セットアップ完了待ち。

## 実機検証

Sony Android 14、検証用display40（1600×900/160）とdisplay41（800×600/160）。証跡は `(local evidence archive; not included)/sog-desktop-0.5/`。

1. 電卓ショートカット追加。初期座標24,24。
2. 長押し → 移動 → タッチドラッグで280,200を保存（touch-position.xml）。
3. SOURCE_MOUSE / BUTTON_PRIMARYの注入で直接ドラッグ。490,301を保存（mouse-position.xml）。グリッドに量子化されていない。
4. 同じアイコンのクリックから電卓起動成功。
5. 通常時の緑色の入力帯がなく、タイトルバーの各ボタンが描画されることを目視確認（chrome-idle.png）。
6. 右辺ホバーで細い線を確認（chrome-hover.png）。右辺ドラッグで900×700→1000×700になり、実際のtask boundsを確認（profiles-after-resize.xml）。
7. 新しい描画ボタンから閉じる成功。
8. ショートカットを1398,671へ移動後、display41で再表示。表示位置は696,440へ補正され、104×96のアイコンが800×536の作業領域内に収まる（small-restored.png）。保存ファイルは再接続前後で完全一致。
9. 既存時計の表示を保持。検証用ショートカットを削除し、初期の空のショートカット一覧へ戻した。

再接続直後の自動表示が一度黒画面になった。対象displayへの明示的なDesktopActivity再表示後に位置復元を確認した。再接続の自動表示が常に安定しているとまでは扱わない。既存の別display・本体操作との競合を含む調査は残る。

任意の吸着と画面外補正は純粋ロジックの4テストを追加。任意の端末、物理USB接続、全種類のマウスでは未検証。今回のマウス確認は実機へのSOURCE_MOUSEイベント注入であり、物理マウス本体の試験ではない。

検証用scrcpyと一時ADBヘルパーのみ片付け、ユーザーの既存scrcpy・ウィジェット・他アプリの設定は保持。コミット・公開なし。
