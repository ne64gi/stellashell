package net.fuyumori.stellashell.feature.search;

import java.util.Objects;
import java.util.function.Consumer;
import net.fuyumori.stellashell.core.search.SearchEngine;

/** Search's public state/command port. No shell, View, task or preference key escapes. */
public interface SearchSettings {
    enum Provider {
        NONE("none", null), GOOGLE("google", SearchEngine.GOOGLE),
        DUCKDUCKGO("duckduckgo", SearchEngine.DUCKDUCKGO),
        BING("bing", SearchEngine.BING), CUSTOM("custom", null);

        public final String key;
        public final SearchEngine engine;

        Provider(String key, SearchEngine engine) { this.key = key; this.engine = engine; }

        public static Provider read(String key) {
            for (Provider value : values()) if (value.key.equals(key)) return value;
            return NONE;
        }
    }

    final class Snapshot {
        public final Provider provider;
        public final String customName, customTemplate;
        public final SearchEngine engine;

        public Snapshot(Provider provider, String name, String template) {
            this.provider = Objects.requireNonNull(provider);
            customName = Objects.requireNonNull(name);
            customTemplate = Objects.requireNonNull(template);
            SearchEngine selected = provider.engine;
            if (provider == Provider.CUSTOM) {
                try { selected = new SearchEngine(name, template); }
                catch (IllegalArgumentException invalid) { selected = null; }
            }
            engine = selected;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Snapshot)) return false;
            Snapshot value = (Snapshot) other;
            return provider == value.provider && customName.equals(value.customName)
                    && customTemplate.equals(value.customTemplate);
        }

        @Override public int hashCode() { return Objects.hash(provider, customName, customTemplate); }
    }

    Snapshot snapshot();
    void save(Provider provider, String customName, String customTemplate);
    /** Change notifications, without an initial callback; caller owns and closes the subscription. */
    AutoCloseable observe(Consumer<Snapshot> consumer);
}
