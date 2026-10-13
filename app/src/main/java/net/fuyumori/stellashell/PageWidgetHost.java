package net.fuyumori.stellashell;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import java.util.function.IntPredicate;

/** Uses the public host/view extension points; normal collection callbacks remain intact. */
final class PageWidgetHost extends AppWidgetHost {
    private final IntPredicate onPage;

    PageWidgetHost(Context context, int hostId, IntPredicate onPage) {
        super(context, hostId);
        this.onPage = onPage;
    }

    @Override protected AppWidgetHostView onCreateView(Context context, int id,
            AppWidgetProviderInfo info) {
        // createView delivers cached RemoteViews before returning: gate the initial
        // update too when recreating a view on a page that is already hidden.
        return new PageWidgetHostView(context, onPage.test(id));
    }

    void releaseViews() { clearViews(); }
}
