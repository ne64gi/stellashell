package net.fuyumori.stellashell.feature.search;

import java.util.Objects;
import net.fuyumori.stellashell.core.search.SearchEngine;

/** One Start window's search lifecycle. All commands run on the window's main loop. */
public final class StartSearchSession implements AutoCloseable {
    public interface Navigator { boolean open(SearchEngine engine, String query); }

    public static final class State {
        public final boolean enabled, visible;
        public final String engineName, query;

        private State(SearchEngine engine, String query) {
            enabled = engine != null;
            visible = enabled && !query.isEmpty();
            engineName = engine == null ? "" : engine.name;
            this.query = query;
        }
    }

    private SearchSettings settings;
    private Navigator navigator;
    private Runnable changed;
    private AutoCloseable subscription;
    private boolean closed;

    public StartSearchSession(SearchSettings settings, Navigator navigator, Runnable changed) {
        this.settings = Objects.requireNonNull(settings);
        this.navigator = Objects.requireNonNull(navigator);
        this.changed = Objects.requireNonNull(changed);
        subscription = settings.observe(ignored -> { if (!closed) this.changed.run(); });
    }

    public State state(String query) {
        return new State(closed ? null : settings.snapshot().engine,
                closed ? "" : SearchEngine.trim(query));
    }

    /** No automatic launch on edits. Read the latest destination at the explicit action. */
    public boolean launch(String query) {
        if (closed) return false;
        SearchEngine engine = settings.snapshot().engine;
        String value = SearchEngine.trim(query);
        return engine != null && !value.isEmpty() && navigator.open(engine, value);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { if (subscription != null) subscription.close(); }
        catch (Exception ignored) { /* Keep the retired window inert even if an adapter fails. */ }
        finally { subscription = null; settings = null; navigator = null; changed = null; }
    }
}
