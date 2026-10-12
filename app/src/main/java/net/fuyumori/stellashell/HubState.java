package net.fuyumori.stellashell;

import android.content.Context;
import android.content.SharedPreferences;

/** Remembers an explicit tab choice; opening, pulling and cancelling do not change it. */
final class HubState {
    private final SharedPreferences preferences;
    private final String key;
    HubState(Context context, boolean phone) {
        this(context.getSharedPreferences("hub_state", Context.MODE_PRIVATE), phone);
    }
    HubState(SharedPreferences preferences, boolean phone) {
        this.preferences = preferences;
        key = phone ? "phone_notifications" : "desktop_notifications";
    }
    boolean notifications() { return preferences.getBoolean(key, false); }
    void select(boolean notifications) {
        if (notifications() != notifications) preferences.edit().putBoolean(key, notifications).apply();
    }
}
