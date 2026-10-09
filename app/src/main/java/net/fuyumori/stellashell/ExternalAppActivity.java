package net.fuyumori.stellashell;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import net.fuyumori.stellashell.core.display.ExclusiveDisplaySession;
import net.fuyumori.stellashell.feature.launch.PublicLauncher;

/** Phone-only waiting surface. No key focus, input injection, wake lock, or system lock. */
public final class ExternalAppActivity extends Activity implements DisplayManager.DisplayListener {
    static final String COMPONENT = "external_app_component";
    static final String DISPLAY = "external_app_display";
    private DisplayManager displays;
    private Display target;
    private ExclusiveDisplaySession.Lease lease;
    private boolean registered, submitted, ending;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable submit = this::submit;

    static void open(Context context, String component, int displayId) {
        if (ExternalAppMode.active()) throw new IllegalStateException("External session already active");
        Intent request = new Intent(context, ExternalAppActivity.class)
                .putExtra(COMPONENT, component).putExtra(DISPLAY, displayId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Bundle options = ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle();
        options.putInt("android.activity.windowingMode", 1);
        context.startActivity(request, options);
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        // A restored task must never silently launch a game again after process death.
        if (saved != null || getDisplay() == null || getDisplay().getDisplayId() != 0
                || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            finish(); return;
        }
        displays = getSystemService(DisplayManager.class);
        int id = getIntent().getIntExtra(DISPLAY, -1);
        target = displays.getDisplay(id);
        try {
            if (id <= 0 || !available()) throw new IllegalArgumentException("Display disconnected");
            if (TaskState.of(this).isBusy() || Launches.pending())
                throw new IllegalStateException("An existing workspace operation is still running");
            new PublicLauncher(this).intent(getIntent().getStringExtra(COMPONENT));
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            // Window-local brightness only; never edit global brightness or blank the physical screen.
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.screenBrightness = 0.08f;
            getWindow().setAttributes(params);
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setPadding(Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28));
            root.setBackgroundColor(0xff101216);
            root.setClickable(true);
            TextView title = new TextView(this);
            title.setText(R.string.external_app_active);
            title.setTextColor(0xffdedfe4); title.setTextSize(22);
            title.setGravity(Gravity.CENTER);
            root.addView(title, new LinearLayout.LayoutParams(-1, -2));
            Button stop = new Button(this);
            stop.setText(R.string.external_app_end_mode);
            stop.setOnClickListener(view -> end());
            LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(-2, Ui.dp(this, 56));
            button.topMargin = Ui.dp(this, 24); root.addView(stop, button);
            setContentView(root);
            displays.registerDisplayListener(this, main); registered = true;
            lease = ExternalAppMode.begin(id);
        } catch (RuntimeException error) {
            Ui.message(this, getString(R.string.external_app_unavailable)); end();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (!ending && lease != null && !submitted) {
            // Let this phone window attach first; launch the external Activity last for input focus.
            getWindow().getDecorView().post(submit);
        }
    }
    private void submit() {
        if (ending || submitted || !ExternalAppMode.current(lease)) return;
        if (!available()) { end(); return; }
        submitted = true;
        try {
            String component = getIntent().getStringExtra(COMPONENT);
            new PublicLauncher(this).launchExternalFullscreen(component, target.getDisplayId());
            Launches.remember(this, component);
        } catch (RuntimeException error) {
            Ui.message(this, getString(R.string.external_app_unavailable)); end();
        }
    }
    private boolean available() {
        return target != null && target.isValid() && (target.getFlags() & Display.FLAG_PRIVATE) == 0;
    }
    private void end() {
        if (ending) return;
        ending = true;
        getWindow().getDecorView().removeCallbacks(submit);
        ExternalAppMode.end(lease); lease = null;
        finish();
    }
    @Override public void onDisplayAdded(int id) {}
    @Override public void onDisplayChanged(int id) { if (target != null && id == target.getDisplayId() && !available()) end(); }
    @Override public void onDisplayRemoved(int id) { if (target != null && id == target.getDisplayId()) end(); }
    @Override protected void onStop() {
        super.onStop();
        // HOME, lock screen, or another phone app is an explicit escape, never fight its focus.
        end();
    }
    @Override protected void onDestroy() {
        end();
        if (registered) { displays.unregisterDisplayListener(this); registered = false; }
        super.onDestroy();
    }
}
