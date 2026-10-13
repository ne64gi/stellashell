package net.fuyumori.stellashell;

import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.widget.RemoteViews;

/** Defers RemoteViews application for a hidden page, not provider or collection work. */
class PageWidgetHostView extends AppWidgetHostView {
    private boolean pageVisible;
    private boolean deferredUpdate, deferredReset;
    private RemoteViews latest;

    PageWidgetHostView(Context context, boolean visible) {
        super(context);
        pageVisible = visible;
    }

    @Override public void updateAppWidget(RemoteViews views) {
        if (pageVisible) {
            super.updateAppWidget(views);
            return;
        }
        // Host callbacks contain the service's complete, merged RemoteViews. Retain one,
        // including null, rather than queuing every update while the page is hidden.
        latest = views;
        deferredUpdate = true;
        // A provider reset must still discard the old layout even when followed by an
        // update with the same layout ID. Collapsing null into that update loses this.
        if (views == null) deferredReset = true;
    }

    void setPageVisible(boolean visible) {
        if (pageVisible == visible) return;
        pageVisible = visible;
        if (!visible || !deferredUpdate) return;
        RemoteViews update = latest;
        boolean reset = deferredReset;
        latest = null;
        deferredUpdate = deferredReset = false;
        if (reset && update != null) super.updateAppWidget(null);
        super.updateAppWidget(update);
    }
}
