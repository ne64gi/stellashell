package net.fuyumori.stellashell;

import android.content.Intent;

/** OS HOME intents only: internal navigation and restored/history tasks are not presses. */
final class HomeRecoveryEntry {
    static final String INTERNAL_HOME = "net.fuyumori.stellashell.INTERNAL_HOME";
    static final String SETTINGS_PAGE = "net.fuyumori.stellashell.SETTINGS_PAGE";

    private HomeRecoveryEntry() {}

    static boolean isPress(Intent intent) {
        return intent != null && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_HOME)
                && !intent.getBooleanExtra(INTERNAL_HOME, false)
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0;
    }
}
