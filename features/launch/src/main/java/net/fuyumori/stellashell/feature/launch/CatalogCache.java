package net.fuyumori.stellashell.feature.launch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * An owner's launcher snapshot, loaded once per generation without holding its monitor during I/O.
 * List membership is immutable and detached; the cache does not copy the entries themselves.
 */
public final class CatalogCache<T> {
    private final Supplier<List<T>> loader;
    private final Object lock = new Object();
    private long generation;
    private Load inFlight;
    private List<T> snapshot;

    private static final class Load {
        final long generation;

        Load(long generation) {
            this.generation = generation;
        }
    }

    public CatalogCache(Supplier<List<T>> loader) {
        this.loader = Objects.requireNonNull(loader);
    }

    /** A nonblocking read for the UI; null means this generation has not been loaded. */
    public List<T> peek() {
        synchronized (lock) {
            return snapshot;
        }
    }

    /**
     * Blocking worker read. Concurrent callers share one load in the current generation.
     * An interrupted waiter keeps its interrupt flag and throws IllegalStateException.
     * Loader failures propagate to their caller, release waiters, and permit another attempt.
     */
    public List<T> get() {
        for (;;) {
            Load load;
            synchronized (lock) {
                if (snapshot != null) return snapshot;
                if (inFlight != null) {
                    try {
                        lock.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while waiting for the catalog", interrupted);
                    }
                    continue;
                }
                load = new Load(generation);
                inFlight = load;
            }

            try {
                List<T> loaded = Collections.unmodifiableList(new ArrayList<>(loader.get()));
                synchronized (lock) {
                    if (generation == load.generation && inFlight == load) {
                        snapshot = loaded;
                        return loaded;
                    }
                }
                // An invalidated load must join/retry the current generation, never return stale data.
            } finally {
                synchronized (lock) {
                    if (inFlight == load) inFlight = null;
                    lock.notifyAll();
                }
            }
        }
    }

    /**
     * Drops the current snapshot and wakes readers. A fresh load may overlap obsolete I/O;
     * completion or failure of that obsolete load cannot clear or replace the fresh load.
     */
    public void invalidate() {
        synchronized (lock) {
            generation++;
            snapshot = null;
            inFlight = null;
            lock.notifyAll();
        }
    }
}
