package net.fuyumori.stellashell;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** External Taskbar stays visible; built-in visibility and both sizes are independent of Dock. */
public final class TaskbarSettingsActivity extends Activity {
    private ShellSettings settings;
    private AutoCloseable settingsSubscription;
    private CheckBox phoneShow;
    private LinearLayout phoneControls;
    private ScaleSlider phoneScale,externalScale;
    private boolean refreshing;

    static void open(Context c,int display){
        try{c.startActivity(new Intent(c,TaskbarSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());}
        catch(RuntimeException e){Ui.message(c,e.getMessage());}
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);settings=ShellSettings.of(this);
        LinearLayout page=DashboardUi.page(this);
        page.addView(DashboardUi.action(this,getString(R.string.taskbar_settings_back),this::finish,false));
        DashboardUi.space(page,16);page.addView(DashboardUi.title(this,getString(R.string.taskbar_settings_title),25));
        Ui.note(page,getString(R.string.taskbar_settings_immediate));
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=Ui.column(this);body.setPadding(0,0,0,Ui.dp(this,28));scroll.addView(body);
        Ui.note(body,getString(R.string.taskbar_settings_note));
        DashboardUi.section(body,getString(R.string.taskbar_settings_phone));LinearLayout card=DashboardUi.card(body);
        phoneShow=checkbox(card,R.string.taskbar_settings_show_phone,
                ()->settings.snapshot().phoneTaskbar.enabled,settings::setPhoneTaskbarEnabled);
        DashboardUi.divider(card);
        phoneControls=Ui.column(this);card.addView(phoneControls);
        phoneScale=new ScaleSlider(phoneControls,()->settings.snapshot().phoneTaskbar.scalePercent,
                settings::setPhoneTaskbarScalePercent);
        DashboardUi.section(body,getString(R.string.taskbar_settings_external));card=DashboardUi.card(body);
        Ui.note(card,getString(R.string.taskbar_settings_external_always));
        externalScale=new ScaleSlider(card,()->settings.snapshot().externalTaskbar.scalePercent,
                settings::setExternalTaskbarScalePercent);
        settingsSubscription=settings.observe((changes,snapshot)->refresh());refresh();
    }
    private CheckBox checkbox(LinearLayout parent,int label,Supplier<Boolean> read,Consumer<Boolean> write){
        CheckBox box=new CheckBox(this);box.setText(label);box.setTextColor(Ui.TEXT);box.setTypeface(Appearance.face);box.setTextSize(15);
        box.setMinHeight(Ui.dp(this,56));box.setPadding(0,Ui.dp(this,8),0,Ui.dp(this,8));box.setChecked(read.get());
        box.setOnCheckedChangeListener((v,checked)->{
            if(refreshing)return;write.accept(checked);
            if(checked&&!Settings.canDrawOverlays(this))Ui.message(this,getString(R.string.ui_allow_the_taskbar_overlay_first));
        });parent.addView(box,new LinearLayout.LayoutParams(-1,-2));return box;
    }
    private void refresh(){
        if(externalScale==null||isDestroyed())return;refreshing=true;
        ShellSettings.Snapshot snapshot=settings.snapshot();
        phoneShow.setChecked(snapshot.phoneTaskbar.enabled);phoneScale.refresh();externalScale.refresh();
        controlsEnabled(phoneControls,phoneShow.isChecked());phoneControls.setAlpha(phoneShow.isChecked()?1f:0.45f);refreshing=false;
    }
    private void controlsEnabled(View view,boolean enabled){
        view.setEnabled(enabled);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)controlsEnabled(group.getChildAt(i),enabled);}
    }
    private final class ScaleSlider {
        final TextView label;final SeekBar bar;final IntSupplier read;final IntConsumer write;
        ScaleSlider(LinearLayout parent,IntSupplier read,IntConsumer write){
            this.read=read;this.write=write;DashboardUi.space(parent,16);label=DashboardUi.text(TaskbarSettingsActivity.this,"",14,Ui.TEXT);parent.addView(label);
            bar=new SeekBar(TaskbarSettingsActivity.this);bar.setMax((NavigationScale.MAX-NavigationScale.MIN)/NavigationScale.STEP);bar.setContentDescription(getString(R.string.taskbar_settings_size));parent.addView(bar,new LinearLayout.LayoutParams(-1,Ui.dp(TaskbarSettingsActivity.this,48)));
            LinearLayout ends=new LinearLayout(TaskbarSettingsActivity.this);TextView first=DashboardUi.text(TaskbarSettingsActivity.this,getString(R.string.taskbar_settings_percent,getString(R.string.taskbar_settings_smaller),NavigationScale.MIN),12,Ui.MUTED),last=DashboardUi.text(TaskbarSettingsActivity.this,getString(R.string.taskbar_settings_percent,getString(R.string.taskbar_settings_larger),NavigationScale.MAX),12,Ui.MUTED);
            first.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);last.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);last.setGravity(android.view.Gravity.RIGHT);
            ends.addView(first,new LinearLayout.LayoutParams(0,-2,1));ends.addView(last,new LinearLayout.LayoutParams(0,-2,1));parent.addView(ends);
            Ui.note(parent,getString(R.string.taskbar_settings_size_note));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(SeekBar seek,int value,boolean user){if(user)write.accept(NavigationScale.MIN+value*NavigationScale.STEP);}
                public void onStartTrackingTouch(SeekBar seek){}public void onStopTrackingTouch(SeekBar seek){}
            });
        }
        void refresh(){int value=NavigationScale.percent(read.getAsInt());bar.setProgress((value-NavigationScale.MIN)/NavigationScale.STEP);bar.setStateDescription(value+"%");label.setText(getString(R.string.taskbar_settings_percent,getString(R.string.taskbar_settings_size),value));}
    }
    @Override public void onDestroy(){close(settingsSubscription);super.onDestroy();}
    private static void close(AutoCloseable closeable){if(closeable!=null)try{closeable.close();}catch(Exception ignored){}}
}
