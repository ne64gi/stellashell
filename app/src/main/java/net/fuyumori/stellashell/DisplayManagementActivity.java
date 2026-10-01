package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;

/** Inventory includes physical screens, but only identified virtual sessions can be closed. */
public final class DisplayManagementActivity extends Activity implements DisplayManager.DisplayListener {
    private LinearLayout rows;private TextView message;private Button reload;private Bridge bridge;private DisplayManager displays;
    private String inventory="";
    private boolean loading,closing,again;private final Runnable changed=this::refresh;
    static void open(Context c){c.startActivity(new Intent(c,DisplayManagementActivity.class));}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);displays=getSystemService(DisplayManager.class);bridge=Bridge.get(this);
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.BG);root.setFitsSystemWindows(true);root.setPadding(Ui.dp(this,20),Ui.dp(this,16),Ui.dp(this,20),Ui.dp(this,16));
        root.addView(Ui.button(this,getString(R.string.ui_back),this::finish));root.addView(Ui.text(this,getString(R.string.displays_title),24,Ui.TEXT));
        message=Ui.text(this,"",14,Ui.MUTED);root.addView(message);
        reload=Ui.button(this,getString(R.string.displays_refresh),this::refresh);root.addView(reload);
        ScrollView scroll=new ScrollView(this);rows=Ui.column(this);scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        displays.registerDisplayListener(this,new Handler(Looper.getMainLooper()));bridge.observe(changed);refresh();
    }
    private void refresh(){
        if(rows==null||isDestroyed()||isFinishing())return;
        if(loading||closing){again=true;return;}
        inventory=inventory();reload.setEnabled(true);rows.removeAllViews();
        if(!bridge.ready()){
            message.setText(R.string.displays_need_bridge);
            for(Display d:displays.getDisplays())if(d.isValid()&&(d.getFlags()&Display.FLAG_PRIVATE)==0)
                rows.addView(Ui.text(this,getString(R.string.output_display,d.getDisplayId()==0?getString(R.string.output_device):d.getName(),d.getDisplayId()),16,Ui.TEXT));
            return;
        }
        loading=true;reload.setEnabled(false);message.setText(R.string.displays_loading);
        bridge.call(IDesktopBridge::displaySessions,(result,error)->{
            loading=false;if(isDestroyed()||isFinishing())return;reload.setEnabled(true);
            if(error!=null){message.setText(error);return;}
            try{render(new JSONArray(result));}catch(JSONException e){message.setText(e.getMessage());}
            if(again){again=false;refresh();}
        });
    }
    private void render(JSONArray list)throws JSONException{
        rows.removeAllViews();message.setText(R.string.displays_note);
        for(int i=0;i<list.length();i++){
            JSONObject row=list.getJSONObject(i);int id=row.getInt("id");String kind=row.getString("kind"),token=row.getString("token");
            String label=getString(R.string.output_display,id==0?getString(R.string.output_device):row.getString("name"),id);
            LinearLayout card=Ui.column(this);card.setPadding(Ui.dp(this,12),Ui.dp(this,12),Ui.dp(this,12),Ui.dp(this,12));
            card.addView(Ui.text(this,label,18,Ui.TEXT));
            Ui.note(card,getString(kind.equals("virtual")?R.string.displays_virtual:kind.equals("device")?R.string.output_device:R.string.displays_physical));
            if(!token.isEmpty())card.addView(Ui.button(this,getString(R.string.displays_close),()->confirm(id,label,token)));
            else Ui.note(card,getString(kind.equals("virtual")?R.string.displays_unidentified:R.string.displays_not_closeable));
            rows.addView(card);
        }
    }
    private void confirm(int id,String label,String token){
        if(closing)return;
        new AlertDialog.Builder(this).setTitle(getString(R.string.displays_close_title,label)).setMessage(R.string.displays_close_note)
                .setNegativeButton(R.string.ui_cancel,null).setPositiveButton(R.string.displays_close,(dialog,which)->{
                    closing=true;reload.setEnabled(false);rows.removeAllViews();message.setText(R.string.displays_closing);
                    bridge.call(s->s.closeDisplaySession(id,token),(result,error)->{
                        closing=false;if(isDestroyed()||isFinishing())return;
                        Ui.message(this,error==null?getString(R.string.displays_closed):error);again=false;refresh();
                    });
                }).show();
    }
    @Override public void onResume(){super.onResume();if(bridge!=null){bridge.connect();refresh();}}
    @Override public void onDisplayAdded(int id){refresh();}
    @Override public void onDisplayRemoved(int id){refresh();}
    private String inventory(){
        StringBuilder value=new StringBuilder();for(Display d:displays.getDisplays())value.append(d.getDisplayId()).append(':').append(d.getName()).append(':').append(d.getFlags()).append(';');return value.toString();
    }
    @Override public void onDisplayChanged(int id){if(!inventory.equals(inventory()))refresh();}
    @Override public void onDestroy(){if(bridge!=null)bridge.remove(changed);if(displays!=null)displays.unregisterDisplayListener(this);super.onDestroy();}
}
