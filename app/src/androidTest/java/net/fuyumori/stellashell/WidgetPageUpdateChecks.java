package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ListView;
import android.widget.RemoteViews;
import android.widget.TextView;

/** Real framework RemoteViews, with no allocation, binding, provider service or saved data. */
final class WidgetPageUpdateChecks {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture extends PageWidgetHostView {
        int defaults;
        Fixture(Context context, boolean visible) { super(context, visible); }
        @Override protected View getDefaultView() {
            defaults++;
            TextView text = new TextView(getContext());
            text.setText("default");
            return text;
        }
        @Override protected View getErrorView() {
            throw new AssertionError("Synthetic RemoteViews failed to apply");
        }
    }

    private static RemoteViews text(String value) {
        RemoteViews views = new RemoteViews("android", android.R.layout.simple_list_item_1);
        views.setTextViewText(android.R.id.text1, value);
        views.setContentDescription(android.R.id.text1, "description " + value);
        return views;
    }

    private static TextView content(PageWidgetHostView view) {
        TextView text = view.findViewById(android.R.id.text1);
        require(text != null, "RemoteViews text content missing");
        return text;
    }

    private static RemoteViews collection(String... values) {
        RemoteViews.RemoteCollectionItems.Builder items = new RemoteViews.RemoteCollectionItems.Builder();
        items.setHasStableIds(true).setViewTypeCount(1);
        for (int i = 0; i < values.length; i++) items.addItem(i, text(values[i]));
        RemoteViews views = new RemoteViews("android", android.R.layout.list_content);
        views.setRemoteAdapter(android.R.id.list, items.build());
        return views;
    }

    static void run(Instrumentation test) {
        Throwable[] failure = {null};
        test.runOnMainSync(() -> {
            try {
                Context context = test.getTargetContext();
                Fixture view = new Fixture(context, false);
                for (int i = 0; i < 50; i++) view.updateAppWidget(text("initial " + i));
                require(view.getChildCount() == 0, "Hidden initial view inflated RemoteViews");
                view.setPageVisible(true);
                require(content(view).getText().toString().equals("initial 49"), "Initial latest update lost");
                require(content(view).getContentDescription().toString().equals("description initial 49"),
                        "Deferred content lost accessibility actions");

                TextView original = content(view);
                int[] applications = {0};
                original.addTextChangedListener(new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) { applications[0]++; }
                    public void afterTextChanged(Editable s) {}
                });
                view.setPageVisible(false);
                for (int i = 0; i < 75; i++) view.updateAppWidget(text("burst " + i));
                require(applications[0] == 0 && original.getText().toString().equals("initial 49"),
                        "Hidden page applied an update");
                view.setPageVisible(true);
                require(applications[0] == 1 && content(view) == original,
                        "Reopen replayed multiple updates or discarded the reusable layout");
                require(content(view).getText().toString().equals("burst 74"), "Latest burst update lost");
                view.setPageVisible(true);
                require(applications[0] == 1, "Repeated visible state replayed an update");

                view.setPageVisible(false);
                view.updateAppWidget(null);
                view.updateAppWidget(text("after reset"));
                require(view.defaults == 0 && content(view) == original, "Hidden reset inflated a layout");
                view.setPageVisible(true);
                require(view.defaults == 1 && content(view) != original,
                        "Null reset followed by the same layout failed to discard old content");
                require(content(view).getText().toString().equals("after reset"), "Reset lost its latest update");

                view.setPageVisible(false);
                view.updateAppWidget(text("superseded"));
                view.updateAppWidget(null);
                view.setPageVisible(true);
                require(view.defaults == 2 && view.getChildCount() == 1
                        && ((TextView)view.getChildAt(0)).getText().toString().equals("default"),
                        "Latest null update was treated as no update");
                view.updateAppWidget(text("visible"));
                require(content(view).getText().toString().equals("visible"), "Visible updates stopped applying");
                view.setPageVisible(false);
                view.setPageVisible(true);
                require(content(view).getText().toString().equals("visible"), "Empty hidden interval changed content");

                // Exercise the host hook used before createView delivers its cached update.
                PageWidgetHost host = new PageWidgetHost(context, 0x575047, id -> id == 7);
                PageWidgetHostView hidden = (PageWidgetHostView)host.onCreateView(context, 8, null);
                hidden.updateAppWidget(text("cached recreation"));
                require(hidden.getChildCount() == 0, "Recreated hidden host view applied cached update");
                hidden.setPageVisible(true);
                require(content(hidden).getText().toString().equals("cached recreation"),
                        "Recreated view lost cached service state");
                PageWidgetHostView visible = (PageWidgetHostView)host.onCreateView(context, 7, null);
                visible.updateAppWidget(text("visible recreation"));
                require(content(visible).getText().toString().equals("visible recreation"),
                        "Host hook gated a visible page");
                host.releaseViews();

                if (Build.VERSION.SDK_INT >= 31) {
                    Fixture listHost = new Fixture(context, true);
                    listHost.updateAppWidget(collection("old row"));
                    ListView list = listHost.findViewById(android.R.id.list);
                    require(list != null && list.getAdapter().getCount() == 1, "Synthetic collection failed to load");
                    listHost.setPageVisible(false);
                    listHost.updateAppWidget(collection("new row", "second row"));
                    require(list.getAdapter().getCount() == 1, "Hidden collection RemoteViews was applied");
                    listHost.setPageVisible(true);
                    list = listHost.findViewById(android.R.id.list);
                    require(list.getAdapter().getCount() == 2, "Latest collection items lost on reopen");
                    // RemoteCollectionItems wraps the row in AdapterChildHostView.
                    View row = list.getAdapter().getView(0, null, list);
                    TextView rowText = row.findViewById(android.R.id.text1);
                    require(rowText != null && rowText.getText().toString().equals("new row"),
                            "Collection row stayed stale");
                }
            } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
        test.waitForIdleSync();
    }
}
