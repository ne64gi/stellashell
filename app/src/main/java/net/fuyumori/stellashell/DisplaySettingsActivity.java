package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.display.ScreenScalePolicy;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONObject;

/** Explicit Apply changes the selected screen's app/text/window density, not its resolution. */
public final class DisplaySettingsActivity extends Activity implements DisplayManager.DisplayListener {
    private DisplayManager displays;private Bridge bridge;
    private Button selector,apply,reset,reload,permissions;
    private TextView dimensions,current,message,draftLabel;
    private SeekBar scale;
    private int selected,draft=100,request;
    private boolean loading,applying,dirty,again,restoredDraft;
    private JSONObject snapshot;
    private String targetIdentity;
    private final Runnable bridgeChanged=this::load;

    static void open(Context c,int display){
        try{c.startActivity(new Intent(c,DisplaySettingsActivity.class).putExtra("display_id",display).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());}
        catch(RuntimeException e){Ui.message(c,c.getString(R.string.display_settings_unavailable));}
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);displays=getSystemService(DisplayManager.class);bridge=Bridge.get(this);
        selected=state==null?getIntent().getIntExtra("display_id",getDisplay()==null?0:getDisplay().getDisplayId()):state.getInt("display",0);
        if(state!=null){draft=state.getInt("draft",100);dirty=state.getBoolean("dirty",false);restoredDraft=dirty;targetIdentity=state.getString("target_identity");}
        LinearLayout page=DashboardUi.page(this);page.addView(DashboardUi.action(this,getString(R.string.display_settings_back),this::finish,false));
        DashboardUi.space(page,16);page.addView(DashboardUi.title(this,getString(R.string.display_settings_title),25));Ui.note(page,getString(R.string.display_settings_note));
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));LinearLayout body=Ui.column(this);body.setPadding(0,0,0,Ui.dp(this,28));scroll.addView(body);
        LinearLayout card=DashboardUi.card(body);
        selector=DashboardUi.action(this,"",this::choose,false);card.addView(selector);
        dimensions=DashboardUi.text(this,"",14,Ui.MUTED);card.addView(dimensions);DashboardUi.space(card,12);
        current=DashboardUi.text(this,"",14,Ui.MUTED);card.addView(current);DashboardUi.space(card,16);
        draftLabel=DashboardUi.text(this,"",16,Ui.TEXT);card.addView(draftLabel);
        scale=new SeekBar(this);scale.setMax((ScreenScalePolicy.MAX-ScreenScalePolicy.MIN)/5);scale.setContentDescription(getString(R.string.display_settings_size));card.addView(scale,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));
        LinearLayout ends=new LinearLayout(this);TextView smaller=DashboardUi.text(this,getString(R.string.display_settings_smaller,ScreenScalePolicy.MIN),12,Ui.MUTED),larger=DashboardUi.text(this,getString(R.string.display_settings_larger,ScreenScalePolicy.MAX),12,Ui.MUTED);larger.setGravity(android.view.Gravity.RIGHT);
        smaller.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);larger.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);ends.addView(smaller,new LinearLayout.LayoutParams(0,-2,1));ends.addView(larger,new LinearLayout.LayoutParams(0,-2,1));card.addView(ends);
        Ui.note(card,getString(R.string.display_settings_apply_note));
        apply=DashboardUi.action(this,getString(R.string.display_settings_apply),()->apply(draft),true);card.addView(apply);
        reset=DashboardUi.action(this,getString(R.string.display_settings_reset),()->apply(ScreenScalePolicy.DEFAULT),false);card.addView(reset);
        message=DashboardUi.text(this,"",14,Ui.MUTED);DashboardUi.space(card,12);card.addView(message);
        reload=DashboardUi.action(this,getString(R.string.display_settings_reload),this::load,false);body.addView(reload);
        permissions=DashboardUi.action(this,getString(R.string.display_settings_permissions),()->startActivity(new Intent(this,PrerequisitesActivity.class).putExtra("manual",true)),false);body.addView(permissions);
        scale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean user){if(user){draft=ScreenScalePolicy.MIN+value*5;dirty=true;render();}}
            public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}
        });
        displays.registerDisplayListener(this,new Handler(Looper.getMainLooper()));bridge.observe(bridgeChanged);render();
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);int next=intent.getIntExtra("display_id",selected);
        if(!applying){selected=next;targetIdentity=null;snapshot=null;dirty=false;request++;load();}
    }
    private List<Display> available(){
        List<Display> list=new ArrayList<>();for(Display display:displays.getDisplays())if(display.isValid()&&(display.getFlags()&Display.FLAG_PRIVATE)==0)list.add(display);
        list.sort(Comparator.comparingInt(Display::getDisplayId));return list;
    }
    private boolean connected(){Display display=displays.getDisplay(selected);return selected>=0&&display!=null&&display.isValid()&&(display.getFlags()&Display.FLAG_PRIVATE)==0;}
    private String name(int id){
        Display display=displays.getDisplay(id);if(display==null||!display.isValid()||(display.getFlags()&Display.FLAG_PRIVATE)!=0)return getString(R.string.display_settings_disconnected);
        if(id==0)return getString(R.string.output_device);String name=display.getName();int count=0,ordinal=0;
        for(Display other:available())if(name.equals(other.getName())){count++;if(other.getDisplayId()<=id)ordinal++;}
        return count>1?getString(R.string.display_settings_screen_numbered,name,ordinal):name;
    }
    private void choose(){
        List<Display> screens=available();if(screens.isEmpty())return;String[] names=new String[screens.size()];int checked=-1;
        for(int i=0;i<names.length;i++){names[i]=name(screens.get(i).getDisplayId());if(screens.get(i).getDisplayId()==selected)checked=i;}
        new AlertDialog.Builder(this).setTitle(R.string.display_settings_screen).setSingleChoiceItems(names,checked,(dialog,which)->{
            selected=screens.get(which).getDisplayId();targetIdentity=null;snapshot=null;dirty=false;request++;dialog.dismiss();load();
        }).setNegativeButton(R.string.ui_cancel,null).show();
    }
    private void render(){
        if(selector==null||isDestroyed())return;boolean busy=loading||applying;
        selector.setText(getString(R.string.display_settings_screen_choice,name(selected)));selector.setEnabled(!applying);
        if(connected()){Point size=new Point();displays.getDisplay(selected).getRealSize(size);dimensions.setText(getString(R.string.display_settings_dimensions,size.x,size.y));}else dimensions.setText("");
        current.setText(snapshot==null?"":getString(R.string.display_settings_current,snapshot.optInt("percent",100)));
        int value=Math.max(ScreenScalePolicy.MIN,Math.min(ScreenScalePolicy.MAX,draft));scale.setProgress((value-ScreenScalePolicy.MIN)/5);scale.setStateDescription(value+"%");draftLabel.setText(getString(R.string.display_settings_percent,value));
        boolean ready=snapshot!=null&&connected()&&bridge.ready()&&!busy;
        scale.setEnabled(ready);apply.setEnabled(ready&&needsApply(value));reset.setEnabled(ready&&!snapshot.optBoolean("standard",false));reload.setEnabled(!busy);
        permissions.setVisibility(bridge.ready()?View.GONE:View.VISIBLE);
    }
    private boolean needsApply(int value){
        if(snapshot==null)return false;
        try{return value==100?!snapshot.optBoolean("standard",false):ScreenScalePolicy.targetDensity(snapshot.optInt("physicalDensity"),value)!=snapshot.optInt("density");}
        catch(IllegalArgumentException ignored){return false;}
    }
    private void load(){
        if(selector==null||isDestroyed()||isFinishing())return;
        if(loading||applying){again=true;return;}
        if(!connected()){snapshot=null;message.setText(R.string.display_settings_disconnected_note);render();return;}
        if(!bridge.ready()){snapshot=null;message.setText(R.string.display_settings_connect_note);render();return;}
        loading=true;int target=selected,revision=++request;message.setText(R.string.display_settings_loading);render();
        bridge.call(service->service.snapshotDisplayScale(target),(result,error)->{
            loading=false;if(isDestroyed()||isFinishing())return;
            if(revision!=request||target!=selected){again=false;load();return;}
            try{
                if(error!=null)throw new IllegalStateException();JSONObject next=new JSONObject(result);
                String identity=next.getString("screenIdentity");
                if(targetIdentity!=null&&!targetIdentity.equals(identity)){snapshot=null;message.setText(R.string.display_settings_changed);render();again=false;return;}
                targetIdentity=identity;
                boolean same=snapshot!=null&&snapshot.optString("identity").equals(next.optString("identity"));
                // A restored/unsaved draft is local UI state, never an automatic write.
                if(!dirty||(!same&&!restoredDraft)){draft=nearest(next.getInt("percent"));dirty=false;}
                restoredDraft=false;snapshot=next;message.setText("");
            }catch(Exception failure){snapshot=null;message.setText(R.string.display_settings_unavailable);}
            render();if(again){again=false;load();}
        });
    }
    private static int nearest(int value){return Math.max(ScreenScalePolicy.MIN,Math.min(ScreenScalePolicy.MAX,Math.round(value/5f)*5));}
    private void apply(int value){
        if(snapshot==null||loading||applying||!connected())return;int target=selected;String identity=snapshot.optString("identity");
        draft=value;applying=true;message.setText(R.string.display_settings_applying);render();
        bridge.call(service->service.applyDisplayScale(target,identity,value),(result,error)->{
            applying=false;if(isDestroyed()||isFinishing())return;dirty=false;
            try{if(error!=null)throw new IllegalStateException();snapshot=new JSONObject(result);draft=nearest(snapshot.getInt("percent"));message.setText(R.string.display_settings_applied);}
            catch(Exception failure){snapshot=null;message.setText(R.string.display_settings_apply_failed);}
            render();if(again){again=false;load();}
        });
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("display",selected);state.putInt("draft",draft);state.putBoolean("dirty",dirty);state.putString("target_identity",targetIdentity);super.onSaveInstanceState(state);}
    @Override public void onResume(){super.onResume();bridge.connect();load();}
    @Override public void onDisplayAdded(int id){render();}
    @Override public void onDisplayRemoved(int id){if(id==selected){snapshot=null;request++;load();}else render();}
    @Override public void onDisplayChanged(int id){if(id==selected)load();}
    @Override public void onDestroy(){if(displays!=null)displays.unregisterDisplayListener(this);if(bridge!=null)bridge.remove(bridgeChanged);super.onDestroy();}
}
