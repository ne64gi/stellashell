package net.fuyumori.stellashell;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

/** Sidebar preferences apply immediately, independently for phone and desktop. */
public final class SidebarSettingsActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String[] PHONE_SIDES={"both","left","right"};
    private static final String[] DOCK_EDGES={"left","right","top","bottom"};
    private SharedPreferences prefs;
    private CheckBox phoneShow,phoneOverApps,desktopShow;
    private LinearLayout phoneControls,desktopControls;
    private Button phoneSide,desktopEdge;
    private Slider phoneHeight,desktopX,desktopY,phoneScale,desktopScale;
    private boolean refreshing;

    static void open(Context c,int display){
        try{c.startActivity(new Intent(c,SidebarSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());}
        catch(RuntimeException e){Ui.message(c,e.getMessage());}
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);prefs=Launches.prefs(this);
        LinearLayout page=DashboardUi.page(this);
        page.addView(DashboardUi.action(this,getString(R.string.sidebar_settings_back),this::finish,false));
        DashboardUi.space(page,16);page.addView(DashboardUi.title(this,getString(R.string.sidebar_settings_title),25));
        Ui.note(page,getString(R.string.sidebar_settings_immediate));
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=Ui.column(this);body.setPadding(0,0,0,Ui.dp(this,28));scroll.addView(body);
        Ui.note(body,getString(R.string.sidebar_settings_start_note));

        DashboardUi.section(body,getString(R.string.sidebar_settings_phone));LinearLayout card=DashboardUi.card(body);
        phoneShow=checkbox(card,R.string.sidebar_settings_show_phone,"phone_sidebar",true);
        DashboardUi.divider(card);phoneControls=Ui.column(this);card.addView(phoneControls);
        phoneSide=choice(phoneControls,R.string.sidebar_settings_call_side,PHONE_SIDES,phoneSideLabels(),"phone_sidebar_side","both",false);
        phoneHeight=new Slider(phoneControls,R.string.sidebar_settings_vertical,"sidebar_height",80,R.string.sidebar_settings_top,R.string.sidebar_settings_bottom);
        phoneScale=new Slider(phoneControls,"phone_dock_scale");
        DashboardUi.divider(phoneControls);
        phoneOverApps=checkbox(phoneControls,R.string.phone_sidebar_over_apps,"phone_sidebar_over_apps",true);

        DashboardUi.section(body,getString(R.string.sidebar_settings_desktop));card=DashboardUi.card(body);
        desktopShow=checkbox(card,R.string.sidebar_settings_show_desktop,"desktop_dock",false);
        DashboardUi.divider(card);desktopControls=Ui.column(this);card.addView(desktopControls);
        desktopEdge=choice(desktopControls,R.string.sidebar_settings_edge,DOCK_EDGES,edgeLabels(),"dock_edge","bottom",true);
        Ui.note(desktopControls,getString(R.string.sidebar_settings_edge_note));
        desktopX=new Slider(desktopControls,R.string.sidebar_settings_horizontal,"dock_x",50,R.string.sidebar_settings_left,R.string.sidebar_settings_right);
        desktopY=new Slider(desktopControls,R.string.sidebar_settings_vertical,"dock_y",100,R.string.sidebar_settings_top,R.string.sidebar_settings_bottom);
        desktopScale=new Slider(desktopControls,"desktop_dock_scale");
        prefs.registerOnSharedPreferenceChangeListener(this);refresh();
    }
    private CheckBox checkbox(LinearLayout parent,int label,String key,boolean fallback){
        CheckBox box=new CheckBox(this);box.setText(label);box.setTextColor(Ui.TEXT);box.setTypeface(Appearance.face);box.setTextSize(15);
        box.setMinHeight(Ui.dp(this,56));box.setPadding(0,Ui.dp(this,8),0,Ui.dp(this,8));box.setChecked(prefs.getBoolean(key,fallback));
        box.setOnCheckedChangeListener((v,checked)->{
            if(refreshing)return;prefs.edit().putBoolean(key,checked).apply();
            if("phone_sidebar".equals(key)&&checked&&!Settings.canDrawOverlays(this))Ui.message(this,getString(R.string.ui_allow_the_taskbar_overlay_first));
        });parent.addView(box,new LinearLayout.LayoutParams(-1,-2));return box;
    }
    private Button choice(LinearLayout parent,int title,String[] values,String[] labels,String key,String fallback,boolean edge){
        Button button=DashboardUi.action(this,"",()->{
            int selected=index(values,prefs.getString(key,fallback),fallback);
            new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels,selected,(dialog,which)->{
                SharedPreferences.Editor edit=prefs.edit().putString(key,values[which]);
                // Snap only the axis perpendicular to the edge; keep its parallel position.
                if(edge){switch(values[which]){
                    case "left":edit.putInt("dock_x",0);break;
                    case "right":edit.putInt("dock_x",100);break;
                    case "top":edit.putInt("dock_y",0);break;
                    default:edit.putInt("dock_y",100);break;
                }}
                edit.apply();dialog.dismiss();
            }).setNegativeButton(R.string.ui_cancel,null).show();
        },false);parent.addView(button);return button;
    }
    private String[] phoneSideLabels(){return new String[]{getString(R.string.sidebar_settings_both),getString(R.string.sidebar_settings_left),getString(R.string.sidebar_settings_right)};}
    private String[] edgeLabels(){return new String[]{getString(R.string.sidebar_settings_left),getString(R.string.sidebar_settings_right),getString(R.string.sidebar_settings_top),getString(R.string.sidebar_settings_bottom)};}
    private static int index(String[] values,String value,String fallback){
        for(int i=0;i<values.length;i++)if(values[i].equals(value))return i;
        for(int i=0;i<values.length;i++)if(values[i].equals(fallback))return i;return 0;
    }
    private void refresh(){
        if(phoneHeight==null||isDestroyed())return;refreshing=true;
        phoneShow.setChecked(prefs.getBoolean("phone_sidebar",true));phoneOverApps.setChecked(prefs.getBoolean("phone_sidebar_over_apps",true));
        desktopShow.setChecked(prefs.getBoolean("desktop_dock",false));
        phoneSide.setText(getString(R.string.sidebar_settings_choice,getString(R.string.sidebar_settings_call_side),phoneSideLabels()[index(PHONE_SIDES,prefs.getString("phone_sidebar_side","both"),"both")]));
        desktopEdge.setText(getString(R.string.sidebar_settings_choice,getString(R.string.sidebar_settings_edge),edgeLabels()[index(DOCK_EDGES,prefs.getString("dock_edge","bottom"),"bottom")]));
        phoneHeight.refresh();desktopX.refresh();desktopY.refresh();phoneScale.refresh();desktopScale.refresh();
        controlsEnabled(phoneControls,phoneShow.isChecked());controlsEnabled(desktopControls,desktopShow.isChecked());refreshing=false;
    }
    private void controlsEnabled(View view,boolean enabled){
        view.setEnabled(enabled);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)controlsEnabled(group.getChildAt(i),enabled);}
        if(view==phoneControls||view==desktopControls)view.setAlpha(enabled?1f:0.45f);
    }
    private final class Slider {
        final TextView label;final SeekBar bar;final int title,fallback,min,max,step;final String key;final boolean scale;
        Slider(LinearLayout parent,int title,String key,int fallback,int start,int end){
            this(parent,title,key,fallback,start,end,0,100,1,false);
        }
        Slider(LinearLayout parent,String key){
            this(parent,R.string.sidebar_settings_size,key,NavigationScale.DEFAULT,R.string.sidebar_settings_smaller,R.string.sidebar_settings_larger,NavigationScale.MIN,NavigationScale.MAX,NavigationScale.STEP,true);
            Ui.note(parent,getString(R.string.sidebar_settings_size_note));
        }
        Slider(LinearLayout parent,int title,String key,int fallback,int start,int end,int min,int max,int step,boolean scale){
            this.title=title;this.key=key;this.fallback=fallback;this.min=min;this.max=max;this.step=step;this.scale=scale;
            DashboardUi.space(parent,16);label=DashboardUi.text(SidebarSettingsActivity.this,"",14,Ui.TEXT);parent.addView(label);
            bar=new SeekBar(SidebarSettingsActivity.this);bar.setMax((max-min)/step);bar.setContentDescription(getString(title));parent.addView(bar,new LinearLayout.LayoutParams(-1,Ui.dp(SidebarSettingsActivity.this,48)));
            String firstLabel=scale?getString(R.string.sidebar_settings_percent,getString(start),min):getString(start),lastLabel=scale?getString(R.string.sidebar_settings_percent,getString(end),max):getString(end);
            LinearLayout ends=new LinearLayout(SidebarSettingsActivity.this);TextView first=DashboardUi.text(SidebarSettingsActivity.this,firstLabel,12,Ui.MUTED),last=DashboardUi.text(SidebarSettingsActivity.this,lastLabel,12,Ui.MUTED);
            first.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);last.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);last.setGravity(android.view.Gravity.RIGHT);
            ends.addView(first,new LinearLayout.LayoutParams(0,-2,1));ends.addView(last,new LinearLayout.LayoutParams(0,-2,1));parent.addView(ends);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(SeekBar seek,int value,boolean user){if(user)prefs.edit().putInt(key,min+value*step).apply();}
                public void onStartTrackingTouch(SeekBar seek){}public void onStopTrackingTouch(SeekBar seek){}
            });
        }
        void refresh(){int saved=prefs.getInt(key,fallback),value=scale?NavigationScale.percent(saved):Math.max(min,Math.min(max,saved));bar.setProgress((value-min)/step);bar.setStateDescription(value+"%");label.setText(getString(R.string.sidebar_settings_percent,getString(title),value));}
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences shared,String key){refresh();}
    @Override public void onDestroy(){if(prefs!=null)prefs.unregisterOnSharedPreferenceChangeListener(this);super.onDestroy();}
}
