package net.fuyumori.stellashell;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Individual hit targets: blank desktop and widgets remain interactive between icons. */
final class DesktopShortcuts {
    private final Activity activity;private final FrameLayout canvas;private final int display;
    private final SharedPreferences positions;
    private final Map<String,View> icons=new LinkedHashMap<>();
    private View moving;
    DesktopShortcuts(Activity activity,FrameLayout canvas,int display){
        this.activity=activity;this.canvas=canvas;this.display=display;positions=activity.getSharedPreferences("shortcut_positions",0);
        canvas.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot){cancelMove();layoutAll();}});
        refresh();
    }
    private int dp(int n){return Ui.dp(activity,n);}
    private int units(int n){return Math.round(n/activity.getResources().getDisplayMetrics().density);}
    private boolean snap(){return Launches.prefs(activity).getBoolean("shortcut_snap",false);}
    void cancelMove(){if(moving!=null){moving.setSelected(false);moving.setAlpha(1f);moving=null;layoutAll();}}
    void refresh(){
        cancelMove();canvas.removeAllViews();icons.clear();
        List<String> items=Launches.desktop(activity);
        // Removed shortcuts do not retain stale coordinates when added again.
        SharedPreferences.Editor cleanup=positions.edit();for(String key:positions.getAll().keySet())if(!items.contains(key))cleanup.remove(key);cleanup.apply();
        for(String component:items)try{
            android.content.pm.ActivityInfo info=activity.getPackageManager().getActivityInfo(ComponentName.unflattenFromString(component),0);
            String label=info.loadLabel(activity.getPackageManager()).toString();
            LinearLayout cell=Ui.column(activity);cell.setGravity(Gravity.CENTER);cell.setPadding(dp(6),dp(6),dp(6),dp(6));
            android.graphics.drawable.StateListDrawable bg=new android.graphics.drawable.StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_selected},Ui.rounded(activity,0x386ee7c8,12));
            bg.addState(new int[]{android.R.attr.state_pressed},Ui.rounded(activity,0x30ffffff,12));
            bg.addState(new int[]{android.R.attr.state_hovered},Ui.rounded(activity,0x18ffffff,12));
            bg.addState(new int[]{android.R.attr.state_focused},Ui.rounded(activity,0x30ffffff,12));
            bg.addState(new int[]{},Ui.rounded(activity,Color.TRANSPARENT,12));cell.setBackground(bg);
            ImageView icon=new ImageView(activity);icon.setImageDrawable(info.loadIcon(activity.getPackageManager()));cell.addView(icon,new LinearLayout.LayoutParams(dp(44),dp(44)));
            TextView name=Ui.text(activity,label,13,Ui.TEXT);name.setGravity(Gravity.CENTER);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setShadowLayer(dp(2),0,dp(1),0xaa000000);cell.addView(name,new LinearLayout.LayoutParams(-1,-2));
            cell.setContentDescription(label);cell.setFocusable(true);cell.setTooltipText(label);
            cell.setOnClickListener(v->{if(moving==cell){cancelMove();return;}Launches.app(activity,component,display);});
            View.OnLongClickListener menu=v->{cancelMove();AppContextMenu.show(activity,cell,component,display,()->{},null,null,()->{moving=cell;cell.setSelected(true);cell.requestFocus();Ui.message(activity,activity.getString(R.string.ui_drag_this_icon_to_place_it_tap_or_press_back_to_cancel));});return true;};
            cell.setOnLongClickListener(menu);cell.setOnContextClickListener(v->menu.onLongClick(v));
            cell.setOnKeyListener((v,key,event)->{if(moving!=cell||event.getAction()!=KeyEvent.ACTION_DOWN)return false;
                if(key==KeyEvent.KEYCODE_ESCAPE){cancelMove();return true;}
                if(key==KeyEvent.KEYCODE_ENTER){commit(component,cell);return true;}
                int dx=key==KeyEvent.KEYCODE_DPAD_LEFT?-24:key==KeyEvent.KEYCODE_DPAD_RIGHT?24:0,dy=key==KeyEvent.KEYCODE_DPAD_UP?-24:key==KeyEvent.KEYCODE_DPAD_DOWN?24:0;
                if(dx==0&&dy==0)return false;FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)cell.getLayoutParams();place(cell,units(p.leftMargin)+dx,units(p.topMargin)+dy);return true;});
            drag(cell,component);icons.put(component,cell);canvas.addView(cell,new FrameLayout.LayoutParams(dp(104),dp(96)));
        }catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
        layoutAll();
    }
    private void layoutAll(){
        if(canvas.getWidth()==0)return;int index=0,rows=Math.max(1,(units(canvas.getHeight())-32)/108);
        for(Map.Entry<String,View> entry:icons.entrySet()){
            int x=24+(index/rows)*116,y=24+(index%rows)*108;index++;
            try{JSONObject j=new JSONObject(positions.getString(entry.getKey(),"{}"));x=j.optInt("x",x);y=j.optInt("y",y);}catch(JSONException ignored){}
            place(entry.getValue(),x,y);
            if(!positions.contains(entry.getKey()))save(entry.getKey(),entry.getValue());
        }
    }
    private void place(View v,int x,int y){
        int aw=units(canvas.getWidth()),ah=units(canvas.getHeight());int w=Math.min(104,Math.max(1,aw)),h=Math.min(96,Math.max(1,ah));
        int[] p=DesktopPlacement.fit(x,y,w,h,aw,ah,snap());FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(dp(w),dp(h));params.leftMargin=dp(p[0]);params.topMargin=dp(p[1]);v.setLayoutParams(params);
    }
    private void save(String component,View v){try{FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)v.getLayoutParams();positions.edit().putString(component,new JSONObject().put("x",units(p.leftMargin)).put("y",units(p.topMargin)).toString()).apply();}catch(JSONException ignored){}}
    private void commit(String component,View v){save(component,v);v.setSelected(false);v.setAlpha(1f);moving=null;}
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Non-drag taps keep the normal click/long-click path; keyboard movement is supported.
    private void drag(View cell,String component){
        cell.setOnTouchListener(new View.OnTouchListener(){float x,y;int left,top;boolean candidate,dragging;
            public boolean onTouch(View v,MotionEvent e){
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                    boolean mouse=e.isFromSource(InputDevice.SOURCE_MOUSE)&&(e.getButtonState()&MotionEvent.BUTTON_PRIMARY)!=0;
                    candidate=mouse||moving==v;dragging=false;x=e.getRawX();y=e.getRawY();FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)v.getLayoutParams();left=units(p.leftMargin);top=units(p.topMargin);return false;
                }
                if(!candidate)return false;
                if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                    float dx=e.getRawX()-x,dy=e.getRawY()-y;
                    if(!dragging&&Math.hypot(dx,dy)>ViewConfiguration.get(activity).getScaledTouchSlop()){dragging=true;v.cancelLongPress();if(moving!=v)cancelMove();moving=v;v.setSelected(true);v.setPressed(false);v.setAlpha(.86f);v.bringToFront();}
                    if(dragging){place(v,left+units(Math.round(dx)),top+units(Math.round(dy)));return true;}
                }
                if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){candidate=false;dragging=false;v.setPressed(false);cancelMove();return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_UP){candidate=false;if(dragging){dragging=false;v.setPressed(false);commit(component,v);return true;}}
                return false;
            }
        });
    }
}
