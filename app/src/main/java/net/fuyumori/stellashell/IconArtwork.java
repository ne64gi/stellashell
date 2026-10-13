package net.fuyumori.stellashell;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import java.util.Objects;
import java.util.function.Supplier;

/** Caches only final, bounded pixels: no source Drawables, package Resources, or Activities. */
final class IconArtwork {
    static final int MAX_SIDE = 256;
    static final long MAX_BYTES = 8L * 1024 * 1024;
    static final int MAX_ENTRIES = 64;
    private static final WeightedLru<Key, Bitmap> cache = new WeightedLru<>(MAX_BYTES, MAX_ENTRIES);
    private static long generation;

    static final class Key {
        // A preference store identity prevents fixture/profile contexts sharing artwork.
        private final SharedPreferences preferences;
        private final String component, requested, custom, pack;
        private final long revision;
        private final int pixels;
        private final Configuration configuration;

        Key(Context context, SharedPreferences preferences, String component, String requested,
                String custom, String pack, long revision, int pixels) {
            this.preferences = preferences;
            this.component = component;
            this.requested = requested;
            this.custom = custom;
            this.pack = pack;
            this.revision = revision;
            this.pixels = pixels;
            configuration = new Configuration(context.getResources().getConfiguration());
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return preferences == key.preferences && revision == key.revision && pixels == key.pixels
                    && Objects.equals(component, key.component) && Objects.equals(requested, key.requested)
                    && Objects.equals(custom, key.custom) && Objects.equals(pack, key.pack)
                    && configuration.equals(key.configuration);
        }
        @Override public int hashCode() {
            return Objects.hash(System.identityHashCode(preferences), component, requested, custom,
                    pack, revision, pixels, configuration);
        }
    }

    static int side(Context context, int requested) {
        int fallback = Math.round(64 * context.getResources().getDisplayMetrics().density);
        return Math.max(1, Math.min(MAX_SIDE, requested > 0 ? requested : fallback));
    }

    static Drawable resolve(Context context, Key key, int pixels, Supplier<Drawable> source) {
        long token;
        synchronized (cache) {
            token = generation;
            Bitmap found = key == null ? null : cache.get(key);
            if (found != null) return drawable(context, found);
        }
        Bitmap rendered = render(source.get(), pixels);
        synchronized (cache) {
            if (key != null && generation == token) {
                // Concurrent requests may render twice; converge on the already-published pixels.
                Bitmap found = cache.get(key);
                if (found != null) {
                    // This duplicate has never escaped to a View; release it immediately.
                    rendered.recycle();
                    return drawable(context, found);
                }
                cache.put(key, rendered, rendered.getAllocationByteCount());
            }
        }
        return drawable(context, rendered);
    }

    private static Drawable drawable(Context context, Bitmap pixels) {
        // Each View owns its drawable bounds/tint/alpha; only immutable-by-convention pixels share.
        return new BitmapDrawable(context.getResources(), pixels).mutate();
    }

    static Bitmap render(Drawable source, int requested) {
        Objects.requireNonNull(source);
        int side = Math.max(1, Math.min(MAX_SIDE, requested));
        int width = source.getIntrinsicWidth(), height = source.getIntrinsicHeight();
        if (width <= 0 || height <= 0) width = height = side;
        float scale = side / (float) Math.max(width, height);
        width = Math.max(1, Math.round(width * scale));
        height = Math.max(1, Math.round(height * scale));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(Bitmap.DENSITY_NONE);
        Rect before = new Rect(source.getBounds());
        try {
            source.setBounds(0, 0, width, height);
            source.draw(new Canvas(bitmap));
            return bitmap;
        } catch (RuntimeException error) {
            bitmap.recycle();
            throw error;
        } finally {
            source.setBounds(before);
        }
    }

    static void clear() { synchronized (cache) { generation++; cache.clear(); } }
    static long cachedBytes() { synchronized (cache) { return cache.weight(); } }
    static int cachedEntries() { synchronized (cache) { return cache.size(); } }
}
