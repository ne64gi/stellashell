# Localization

- Default resources (`app/src/main/res/values/strings.xml`): English.
- Japanese (`values-ja/strings.xml`): selected automatically from the Android locale.
- Android 13+ app-language settings expose English and Japanese through `locales_config.xml`. Older versions follow system language. Unsupported languages fall back to English.
- Translate resource values, not keys. Preserve format placeholders such as `%1$s` / `%d` and Android escaping. Quoted values intentionally preserve spacing in diagnostic prefixes.
- `app_count` uses plurals. Window accessibility labels use formatted strings so translators can change word order.
- App/provider names and third-party exception details remain provider-owned. Known shell/backend errors are localized at the UI boundary; backend diagnostics retain stable English messages.
- Display overlays rebuild when configuration changes. Layout/density behavior is otherwise unchanged.
- Device instrumentation checks Japanese, English, unsupported-locale fallback, plurals, formatted accessibility labels, and a backend error without changing the device language.

To add a language, add a complete `values-<locale>/strings.xml` and the locale to `locales_config.xml`. Run lint and the resource-key/placeholder checks before shipping.
