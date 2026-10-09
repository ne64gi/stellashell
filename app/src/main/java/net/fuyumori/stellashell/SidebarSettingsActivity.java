package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.navigation.NavigationScale;
import net.fuyumori.stellashell.core.layout.EdgeDockReveal.Method;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Sidebar preferences apply immediately, independently for phone and desktop. */
public final class SidebarSettingsActivity extends Activity {
    private ShellSettings settings;
    private AutoCloseable settingsSubscription;
    private CheckBox phoneShow,phoneOverApps,desktopShow,desktopByHandle;
    private LinearLayout phoneControls,desktopControls;
    private LinearLayout phoneHandleControls;
    private Button gestureTutorial;
    private static final ShellSettings.PhoneSide[] PHONE_SIDES={ShellSettings.PhoneSide.BOTH,ShellSettings.PhoneSide.RIGHT,ShellSettings.PhoneSide.LEFT,ShellSettings.PhoneSide.GESTURE};
    private Button phoneSide,phoneLandscapeEdge,desktopEdge,phoneOpenMethod,desktopOpenMethod;
    private TextView desktopPositionNote,phoneMethodNote,desktopMethodNote;
    private Slider phoneHeight,phoneLandscapeX,phoneLandscapeY,desktopX,desktopY,phoneScale,desktopScale;
    private boolean refreshing;

    static void open(Context c,int display){
        try{c.startActivity(new Intent(c,SidebarSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());}
        catch(RuntimeException e){Ui.message(c,e.getMessage());}
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);settings=ShellSettings.of(this);
        LinearLayout page=DashboardUi.page(this);
        page.addView(DashboardUi.action(this,getString(R.string.sidebar_settings_back),this::finish,false));
        DashboardUi.space(page,16);page.addView(DashboardUi.title(this,getString(R.string.sidebar_settings_title),25));
        Ui.note(page,getString(R.string.sidebar_settings_immediate));
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=Ui.column(this);body.setPadding(0,0,0,Ui.dp(this,28));scroll.addView(body);
        Ui.note(body,getString(R.string.sidebar_settings_start_note));

        DashboardUi.section(body,getString(R.string.sidebar_settings_phone));LinearLayout card=DashboardUi.card(body);
        phoneShow=checkbox(card,R.string.sidebar_settings_show_phone,
                ()->settings.snapshot().phoneDock.enabled,settings::setPhoneDockEnabled);
        DashboardUi.divider(card);phoneControls=Ui.column(this);card.addView(phoneControls);
        phoneSide=choice(phoneControls,R.string.dock_handle_position,phoneSideLabels(),
                this::phoneSideIndex,index->{
                    if(PHONE_SIDES[index]==ShellSettings.PhoneSide.GESTURE)DockGestureTutorial.show(this,()->settings.setPhoneDockSide(ShellSettings.PhoneSide.GESTURE));
                    else settings.setPhoneDockSide(PHONE_SIDES[index]);
                });
        gestureTutorial=DashboardUi.action(this,getString(R.string.dock_gesture_tutorial),()->DockGestureTutorial.show(this,()->{}),false);phoneControls.addView(gestureTutorial);
        phoneHandleControls=Ui.column(this);phoneControls.addView(phoneHandleControls);
        phoneOpenMethod=choice(phoneHandleControls,R.string.sidebar_settings_open_method,methodLabels(),
                ()->settings.snapshot().phoneDock.openMethod.ordinal(),index->settings.setPhoneDockOpenMethod(Method.values()[index]));
        phoneMethodNote=DashboardUi.text(this,"",13,Ui.MUTED);phoneHandleControls.addView(phoneMethodNote);
        DashboardUi.section(phoneHandleControls,getString(R.string.sidebar_settings_portrait));
        phoneHeight=new Slider(phoneHandleControls,R.string.sidebar_settings_vertical,()->settings.snapshot().phoneDock.triggerPercent,
                settings::setPhoneDockTriggerPercent,R.string.sidebar_settings_top,R.string.sidebar_settings_bottom,0,100,1,false);
        DashboardUi.section(phoneHandleControls,getString(R.string.sidebar_settings_landscape));
        phoneLandscapeEdge=choice(phoneHandleControls,R.string.sidebar_settings_edge,edgeLabels(),
                ()->settings.snapshot().phoneLandscapeDock.edge.ordinal(),index->settings.setPhoneLandscapeDockEdge(ShellSettings.DockEdge.values()[index]));
        Ui.note(phoneHandleControls,getString(R.string.sidebar_settings_landscape_note));
        phoneLandscapeX=new Slider(phoneHandleControls,R.string.sidebar_settings_horizontal,
                ()->settings.snapshot().phoneLandscapeDock.positionPercent,settings::setPhoneLandscapeDockPosition,
                R.string.sidebar_settings_left,R.string.sidebar_settings_right,0,100,1,false);
        phoneLandscapeY=new Slider(phoneHandleControls,R.string.sidebar_settings_vertical,
                ()->settings.snapshot().phoneLandscapeDock.positionPercent,settings::setPhoneLandscapeDockPosition,
                R.string.sidebar_settings_top,R.string.sidebar_settings_bottom,0,100,1,false);
        DashboardUi.divider(phoneControls);
        phoneScale=new Slider(phoneControls,()->settings.snapshot().phoneDock.scalePercent,
                settings::setPhoneDockScalePercent);
        DashboardUi.divider(phoneControls);
        phoneOverApps=checkbox(phoneControls,R.string.phone_sidebar_over_apps,
                ()->settings.snapshot().phoneDock.overApps,settings::setPhoneDockOverApps);

        DashboardUi.section(body,getString(R.string.sidebar_settings_desktop));card=DashboardUi.card(body);
        desktopShow=checkbox(card,R.string.sidebar_settings_show_desktop,
                ()->settings.snapshot().externalDock.enabled,settings::setExternalDockEnabled);
        DashboardUi.divider(card);desktopControls=Ui.column(this);card.addView(desktopControls);
        desktopByHandle=checkbox(desktopControls,R.string.sidebar_settings_by_handle,
                ()->settings.snapshot().externalDock.revealByHandle,settings::setExternalDockRevealByHandle);
        desktopOpenMethod=choice(desktopControls,R.string.sidebar_settings_open_method,methodLabels(),
                ()->settings.snapshot().externalDock.openMethod.ordinal(),index->settings.setExternalDockOpenMethod(Method.values()[index]));
        desktopMethodNote=DashboardUi.text(this,"",13,Ui.MUTED);desktopControls.addView(desktopMethodNote);
        desktopEdge=choice(desktopControls,R.string.sidebar_settings_edge,edgeLabels(),
                ()->settings.snapshot().externalDock.edge.ordinal(),index->settings.setExternalDockEdge(ShellSettings.DockEdge.values()[index]));
        desktopPositionNote=DashboardUi.text(this,"",13,Ui.MUTED);desktopControls.addView(desktopPositionNote);
        desktopX=new Slider(desktopControls,R.string.sidebar_settings_horizontal,
                ()->settings.snapshot().externalDock.xPercent,
                value->{ShellSettings.Snapshot current=settings.snapshot();settings.setExternalDockPosition(value,current.externalDock.yPercent);},
                R.string.sidebar_settings_left,R.string.sidebar_settings_right,0,100,1,false);
        desktopY=new Slider(desktopControls,R.string.sidebar_settings_vertical,
                ()->settings.snapshot().externalDock.yPercent,
                value->{ShellSettings.Snapshot current=settings.snapshot();settings.setExternalDockPosition(current.externalDock.xPercent,value);},
                R.string.sidebar_settings_top,R.string.sidebar_settings_bottom,0,100,1,false);
        desktopScale=new Slider(desktopControls,()->settings.snapshot().externalDock.scalePercent,
                settings::setExternalDockScalePercent);
        settingsSubscription=settings.observe((changes,snapshot)->refresh());refresh();
    }
    private CheckBox checkbox(LinearLayout parent,int label,Supplier<Boolean> read,Consumer<Boolean> write){
        CheckBox box=new CheckBox(this);box.setText(label);box.setTextColor(Ui.TEXT);box.setTypeface(Appearance.face);box.setTextSize(15);
        box.setMinHeight(Ui.dp(this,56));box.setPadding(0,Ui.dp(this,8),0,Ui.dp(this,8));box.setChecked(read.get());
        box.setOnCheckedChangeListener((v,checked)->{
            if(refreshing)return;write.accept(checked);
            if(label==R.string.sidebar_settings_show_phone&&checked&&!Settings.canDrawOverlays(this))Ui.message(this,getString(R.string.ui_allow_the_taskbar_overlay_first));
        });parent.addView(box,new LinearLayout.LayoutParams(-1,-2));return box;
    }
    private Button choice(LinearLayout parent,int title,String[] labels,IntSupplier selected,IntConsumer write){
        Button button=DashboardUi.action(this,"",()->{
            new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels,selected.getAsInt(),(dialog,which)->{
                write.accept(which);dialog.dismiss();
            }).setNegativeButton(R.string.ui_cancel,null).show();
        },false);parent.addView(button);return button;
    }
    private int phoneSideIndex(){ShellSettings.PhoneSide side=settings.snapshot().phoneDock.phoneSide;for(int i=0;i<PHONE_SIDES.length;i++)if(PHONE_SIDES[i]==side)return i;return 0;}
    private String[] phoneSideLabels(){return new String[]{getString(R.string.sidebar_settings_both),getString(R.string.sidebar_settings_right),getString(R.string.sidebar_settings_left),getString(R.string.dock_gesture_title)};}
    private String[] edgeLabels(){return new String[]{getString(R.string.sidebar_settings_left),getString(R.string.sidebar_settings_right),getString(R.string.sidebar_settings_top),getString(R.string.sidebar_settings_bottom)};}
    private String[] methodLabels(){return new String[]{getString(R.string.sidebar_settings_single_tap),getString(R.string.sidebar_settings_double_tap),getString(R.string.sidebar_settings_swipe)};}
    private int methodNote(Method method){
        switch(method){
            case SINGLE_TAP:return R.string.sidebar_settings_single_tap_note;
            case DOUBLE_TAP:return R.string.sidebar_settings_double_tap_note;
            default:return R.string.sidebar_settings_pull_note;
        }
    }
    private void refresh(){
        if(phoneHeight==null||isDestroyed())return;refreshing=true;
        ShellSettings.Snapshot snapshot=settings.snapshot();
        phoneShow.setChecked(snapshot.phoneDock.enabled);phoneOverApps.setChecked(snapshot.phoneDock.overApps);
        desktopShow.setChecked(snapshot.externalDock.enabled);desktopByHandle.setChecked(snapshot.externalDock.revealByHandle);
        phoneOpenMethod.setText(getString(R.string.sidebar_settings_choice,getString(R.string.sidebar_settings_open_method),methodLabels()[snapshot.phoneDock.openMethod.ordinal()]));
        desktopOpenMethod.setText(getString(R.string.sidebar_settings_choice,getString(R.string.sidebar_settings_open_method),methodLabels()[snapshot.externalDock.openMethod.ordinal()]));
        phoneMethodNote.setText(methodNote(snapshot.phoneDock.openMethod));desktopMethodNote.setText(methodNote(snapshot.externalDock.openMethod));
        desktopOpenMethod.setVisibility(snapshot.externalDock.revealByHandle?View.VISIBLE:View.GONE);
        desktopMethodNote.setVisibility(snapshot.externalDock.revealByHandle?View.VISIBLE:View.GONE);
        phoneSide.setText(getString(R.string.sidebar_settings_choice,getString(R.string.dock_handle_position),phoneSideLabels()[phoneSideIndex()]));
        boolean gesture=snapshot.phoneDock.phoneSide==ShellSettings.PhoneSide.GESTURE;
        phoneHandleControls.setVisibility(gesture?View.GONE:View.VISIBLE);gestureTutorial.setVisibility(gesture?View.VISIBLE:View.GONE);
        phoneLandscapeEdge.setText(getString(R.string.sidebar_settings_choice,getString(R.string.sidebar_settings_edge),edgeLabels()[snapshot.phoneLandscapeDock.edge.ordinal()]));
        desktopEdge.setText(getString(R.string.sidebar_settings_choice,getString(R.string.sidebar_settings_edge),edgeLabels()[snapshot.externalDock.edge.ordinal()]));
        boolean landscapeHorizontal=horizontalEdge(snapshot.phoneLandscapeDock.edge),desktopHorizontal=horizontalEdge(snapshot.externalDock.edge);
        phoneLandscapeX.setVisible(landscapeHorizontal);phoneLandscapeY.setVisible(!landscapeHorizontal);
        desktopX.setVisible(!snapshot.externalDock.revealByHandle||desktopHorizontal);
        desktopY.setVisible(!snapshot.externalDock.revealByHandle||!desktopHorizontal);
        desktopPositionNote.setText(snapshot.externalDock.revealByHandle?R.string.sidebar_settings_handle_note:R.string.sidebar_settings_edge_note);
        phoneHeight.refresh();phoneLandscapeX.refresh();phoneLandscapeY.refresh();desktopX.refresh();desktopY.refresh();phoneScale.refresh();desktopScale.refresh();
        controlsEnabled(phoneControls,phoneShow.isChecked());controlsEnabled(desktopControls,desktopShow.isChecked());refreshing=false;
    }
    private static boolean horizontalEdge(ShellSettings.DockEdge edge){return edge==ShellSettings.DockEdge.TOP||edge==ShellSettings.DockEdge.BOTTOM;}
    private void controlsEnabled(View view,boolean enabled){
        view.setEnabled(enabled);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)controlsEnabled(group.getChildAt(i),enabled);}
        if(view==phoneControls||view==desktopControls)view.setAlpha(enabled?1f:0.45f);
    }
    private final class Slider {
        LinearLayout container;TextView label;SeekBar bar;final int title,min,max,step;final IntSupplier read;final IntConsumer write;final boolean scale;
        Slider(LinearLayout parent,int title,IntSupplier read,IntConsumer write,int start,int end,int min,int max,int step,boolean scale){
            this.title=title;this.read=read;this.write=write;this.min=min;this.max=max;this.step=step;this.scale=scale;
            create(parent,start,end);
        }
        Slider(LinearLayout parent,IntSupplier read,IntConsumer write){
            this(parent,R.string.sidebar_settings_size,read,write,R.string.sidebar_settings_smaller,R.string.sidebar_settings_larger,NavigationScale.MIN,NavigationScale.MAX,NavigationScale.STEP,true);
            Ui.note(parent,getString(R.string.sidebar_settings_size_note));
        }
        private void create(LinearLayout parent,int start,int end){
            container=Ui.column(SidebarSettingsActivity.this);parent.addView(container);parent=container;
            DashboardUi.space(parent,16);label=DashboardUi.text(SidebarSettingsActivity.this,"",14,Ui.TEXT);parent.addView(label);
            bar=new SeekBar(SidebarSettingsActivity.this);bar.setMax((max-min)/step);bar.setContentDescription(getString(title));parent.addView(bar,new LinearLayout.LayoutParams(-1,Ui.dp(SidebarSettingsActivity.this,48)));
            String firstLabel=scale?getString(R.string.sidebar_settings_percent,getString(start),min):getString(start),lastLabel=scale?getString(R.string.sidebar_settings_percent,getString(end),max):getString(end);
            LinearLayout ends=new LinearLayout(SidebarSettingsActivity.this);TextView first=DashboardUi.text(SidebarSettingsActivity.this,firstLabel,12,Ui.MUTED),last=DashboardUi.text(SidebarSettingsActivity.this,lastLabel,12,Ui.MUTED);
            first.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);last.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);last.setGravity(android.view.Gravity.RIGHT);
            ends.addView(first,new LinearLayout.LayoutParams(0,-2,1));ends.addView(last,new LinearLayout.LayoutParams(0,-2,1));parent.addView(ends);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(SeekBar seek,int value,boolean user){if(user)write.accept(min+value*step);}
                public void onStartTrackingTouch(SeekBar seek){}public void onStopTrackingTouch(SeekBar seek){}
            });
        }
        void setVisible(boolean visible){container.setVisibility(visible?View.VISIBLE:View.GONE);}
        void refresh(){int saved=read.getAsInt(),value=scale?NavigationScale.percent(saved):Math.max(min,Math.min(max,saved));bar.setProgress((value-min)/step);bar.setStateDescription(value+"%");label.setText(getString(R.string.sidebar_settings_percent,getString(title),value));}
    }
    @Override public void onDestroy(){close(settingsSubscription);super.onDestroy();}
    private static void close(AutoCloseable closeable){if(closeable!=null)try{closeable.close();}catch(Exception ignored){}}
}
