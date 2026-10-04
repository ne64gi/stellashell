# Launch feature

通常Android起動の解決、launcher catalog、pins／recent／desktop配置を所有するAndroid library。依存は `:core` のみ。

- `AppCatalog(Context).entries()`: 自アプリを除外し、componentで重複排除、既存の`Collator`順で返す。catalog cacheは持たない。Entryのidentity／labelはimmutable、iconはAndroid Drawable参照。
- `PublicLauncher(Context).intent(component)`: 指定されたaccessible launcher aliasを保持し、internal／protected／stale entryだけを同packageのaccessible launcherへ解決する。
- `PublicLauncher.launch(component, displayId)`: 従来のflagsとActivityOptionsで起動する。成功後のrecent保存とdisplay検証はapp側の判断。
- `LaunchItems(SharedPreferences)`: 注入された既存保存領域へ項目別commandsを行う。保存ファイルを開くこと、画面選択、初回phone profile初期化は担当しない。

## 保存契約

| Profile / Surface | 既存キー | 上限 |
| --- | --- | --- |
| PHONE / DOCK | `phone_pinned` | なし |
| PHONE / TASKBAR | `phone_taskbar_pinned` | 6 |
| DESKTOP / DOCK | `dock_pinned` | なし |
| DESKTOP / TASKBAR | `pinned` | 6 |
| PHONE desktop | `phone_desktop_shortcuts` | なし |
| DESKTOP desktop | `desktop_shortcuts` | なし |
| 共通recent | `recent` | 重複なし、最新順6件 |

newline保存／split、pinsの挿入順、既存desktop placementを維持する。`shortcuts(profile)`は従来どおりPHONE DockまたはDESKTOP Taskbarのpinsに共通recentを重複なしで追加する。

`snapshot(profile)`と各readerは最新値からdetached immutable listを返す。commandsは同SharedPreferencesのlock上でlatest read／modify／writeし、古いsnapshotを保存し直さない。無関係なキーは書き換えない。

`togglePin`／`remember`はcoreのcomponent policyで検証する。desktop placementには`group:`参照も含むため、`toggleDesktop`の前にappの`GroupEntries.validate`でcomponent／group存在を検証する。6件上限時のUIメッセージもapp側。

`removeDesktopReference(reference)`は両profileの既存desktopキーだけから一致する最初の1件を削除する。1回のEditor適用で反映し、未作成キーを作らず、Start pinsなど他領域を変更しない。group削除時の存在検証と他領域の清掃はapp側。

privileged Bridge起動、HOME、PiP、task identity／queue／role assignmentはこのfeatureに含まない。`LauncherFeatureChecks`はnonce preferencesだけで保存契約を検証し、実PackageManagerのalias／公開entry回帰は既存`LauncherEntryChecks`が担当する。

全体の依存方向と拡張手順は [モジュール構成](../../docs/MODULES.md) を参照。
