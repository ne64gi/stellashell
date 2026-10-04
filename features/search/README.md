# Search feature

検索設定とStart windowごとの検索操作を所有するAndroid library。依存は `:core` のみ。

- `SearchSettings`: immutable snapshot / save / observe の公開port。
- `WebSearchSettings`: `web_search`保存領域の唯一のadapter。既存キー・型・初期OFFを維持する。
- `StartSearchSession`: 最新の検索先を明示clickで使い、closeで購読とnavigatorを解放する。View、Activity、app型には依存しない。
- coreの`SearchEngine`: URLの検証・encodingだけを担当し、通信しない。

画面・通常Android起動・display検証はapp側adapter。設定Activityの既存component名を移動しない。検索文字列の永続化・入力時通信は行わない。

```sh
./gradlew :feature-search:testDebugUnitTest :feature-search:lintDebug
```

全体の依存方向と拡張手順は [モジュール構成](../../docs/MODULES.md) を参照。
