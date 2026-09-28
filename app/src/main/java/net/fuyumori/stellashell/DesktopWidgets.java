package net.fuyumori.stellashell;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Standard Android widgets. Binding/configuration remain system/provider-owned flows. */
final class DesktopWidgets {
    private static final int HOST=0x534f47, BIND=7101, CONFIGURE=7102;
    private final Activity activity;
    private final FrameLayout canvas;
    private final AppWidgetManager manager;
    private final AppWidgetHost host;
    private final android.content.SharedPreferences prefs;
    private final List<Entry> entries=new ArrayList<>();
    private boolean editing;
    private int pending=-1;
    private static final class Entry {
        int id,x,y,w,h;
        FrameLayout frame;
        AppWidgetHostView view;
        AppWidgetProviderInfo info;
    }
    DesktopWidgets(Activity a,FrameLayout c){
        activity=a;canvas=c;manager=AppWidgetManager.getInstance(a);host=new AppWidgetHost(a,HOST);
        prefs=a.getSharedPreferences("desktop_widgets",0);pending=prefs.getInt("pending",-1);
        try{
            JSONArray array=new JSONArray(prefs.getString("items","[]"));
            for(int i=0;i<array.length();i++){
                JSONObject j=array.getJSONObject(i);Entry e=new Entry();e.id=j.getInt("id");e.x=j.optInt("x",24);e.y=j.optInt("y",140);e.w=Math.max(80,j.optInt("w",320));e.h=Math.max(60,j.optInt("h",180));entries.add(e);
            }
        }catch(JSONException e){Ui.message(a,"ウィジェットの保存情報を読み込めませんでした");}
        // Reconcile only this host's unreferenced allocations, never other launchers' widgets.
        Set<Integer> retained=new HashSet<>();for(Entry e:entries)retained.add(e.id);if(pending>=0)retained.add(pending);
        for(int id:host.getAppWidgetIds())if(!retained.contains(id))host.deleteAppWidgetId(id);
        canvas.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)relayout();});
        rebuild();
    }
    void start(){try{host.startListening();}catch(RuntimeException e){Ui.message(activity,"ウィジェット更新: "+e.getMessage());}}
    void stop(){host.stopListening();}
    void destroy(){if(activity.isFinishing()&&pending>=0)cancel();}
    boolean isEditing(){return editing;}
    void setEditing(boolean value){editing=value;rebuild();}
    void choose(){
        if(pending>=0){new AlertDialog.Builder(activity).setMessage("追加途中のウィジェットがあります。取り消して選び直しますか？").setNegativeButton("戻る",null).setPositiveButton("選び直す",(d,w)->{cancel();choose();}).show();return;}
        List<AppWidgetProviderInfo> providers=new ArrayList<>(manager.getInstalledProviders());
        providers.removeIf(p->(p.widgetCategory&AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)==0);
        providers.sort(Comparator.comparing(p->p.loadLabel(activity.getPackageManager()),String.CASE_INSENSITIVE_ORDER));
        String[] labels=new String[providers.size()];for(int i=0;i<labels.length;i++){AppWidgetProviderInfo p=providers.get(i);labels[i]=p.loadLabel(activity.getPackageManager())+"\n"+p.provider.getPackageName();}
        if(labels.length==0){Ui.message(activity,"利用できるウィジェットがありません");return;}
        new AlertDialog.Builder(activity).setTitle("ウィジェットを追加").setItems(labels,(d,n)->allocate(providers.get(n))).setNegativeButton("キャンセル",null).show();
    }
    private void allocate(AppWidgetProviderInfo info){
        try{
            pending=host.allocateAppWidgetId();prefs.edit().putInt("pending",pending).apply();
            Bundle options=new Bundle();options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
            if(manager.bindAppWidgetIdIfAllowed(pending,info.getProfile(),info.provider,options))configure();
            else{
                Intent intent=new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,pending).putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,info.provider).putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE,info.getProfile()).putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS,options);
                activity.startActivityForResult(intent,BIND);
            }
        }catch(RuntimeException e){cancel();Ui.message(activity,"追加できません: "+e.getMessage());}
    }
    boolean result(int request,int result){
        if(request!=BIND&&request!=CONFIGURE)return false;
        if(pending<0)return true;
        if(result!=Activity.RESULT_OK){cancel();return true;}
        if(request==BIND)configure();else finishAdd();return true;
    }
    private void configure(){
        AppWidgetProviderInfo info=manager.getAppWidgetInfo(pending);
        if(info==null){cancel();Ui.message(activity,"ウィジェットの割り当てが完了しませんでした");return;}
        if(info.configure!=null){
            try{host.startAppWidgetConfigureActivityForResult(activity,pending,0,CONFIGURE,null);}
            catch(RuntimeException e){cancel();Ui.message(activity,"初期設定を開けません: "+e.getMessage());}
        }else finishAdd();
    }
    private void finishAdd(){
        AppWidgetProviderInfo info=manager.getAppWidgetInfo(pending);if(info==null){cancel();return;}
        Entry e=new Entry();e.id=pending;e.x=24+entries.size()%5*24;e.y=140+entries.size()%5*24;
        e.w=Math.max(240,pxToDp(info.minWidth));e.h=Math.max(120,pxToDp(info.minHeight));entries.add(e);
        // Commit the entry and clear pending together: recreation cannot orphan a bound ID.
        pending=-1;save();editing=true;rebuild();Ui.message(activity,info.resizeMode==AppWidgetProviderInfo.RESIZE_NONE?"上の帯で移動できます。このウィジェットは提供元が固定サイズに指定しています":"上の帯で移動、右下でサイズ変更。空白のメニューから編集を終了できます");
    }
    private void cancel(){if(pending>=0)host.deleteAppWidgetId(pending);pending=-1;prefs.edit().remove("pending").apply();}
    private int pxToDp(int value){return Math.round(value/activity.getResources().getDisplayMetrics().density);}
    private int dp(int n){return Ui.dp(activity,n);}
    private void save(){
        JSONArray array=new JSONArray();for(Entry e:entries)try{array.put(new JSONObject().put("id",e.id).put("x",e.x).put("y",e.y).put("w",e.w).put("h",e.h));}catch(JSONException ignored){}
        prefs.edit().putString("items",array.toString()).putInt("pending",pending).apply();
    }
    private void rebuild(){
        canvas.removeAllViews();for(Entry e:entries){
            e.info=manager.getAppWidgetInfo(e.id);e.frame=new FrameLayout(activity);
            if(e.info!=null){
                e.view=host.createView(activity,e.id,e.info);e.frame.addView(e.view,new FrameLayout.LayoutParams(-1,-1));
            }else{e.view=null;TextView missing=Ui.text(activity,"利用できないウィジェット\n編集モードで削除できます",14,Ui.MUTED);e.frame.addView(missing);}
            if(editing){
                e.frame.setBackground(Ui.rounded(activity,Ui.PANEL,8));
                // Editing intercepts provider touches; normal mode leaves all gestures to RemoteViews.
                View shield=new View(activity);shield.setClickable(true);e.frame.addView(shield,new FrameLayout.LayoutParams(-1,-1));
                LinearLayout bar=new LinearLayout(activity);bar.setBackgroundColor(Ui.PANEL);
                TextView move=Ui.text(activity,"移動 ⋮ "+(e.info==null?"ウィジェット":e.info.loadLabel(activity.getPackageManager())),13,Ui.TEXT);move.setGravity(Gravity.CENTER_VERTICAL);move.setPadding(dp(8),0,0,0);move.setContentDescription("ウィジェットを移動");bar.addView(move,new LinearLayout.LayoutParams(0,-1,1));gesture(move,e,false);
                TextView remove=Ui.text(activity,"×",22,Ui.TEXT);remove.setGravity(Gravity.CENTER);remove.setContentDescription("ウィジェットを削除");bar.addView(remove,new LinearLayout.LayoutParams(dp(40),-1));remove.setOnClickListener(v->new AlertDialog.Builder(activity).setMessage("このウィジェットを削除しますか？").setNegativeButton("キャンセル",null).setPositiveButton("削除",(d,w)->{entries.remove(e);save();host.deleteAppWidgetId(e.id);rebuild();}).show());
                e.frame.addView(bar,new FrameLayout.LayoutParams(-1,dp(32),Gravity.TOP));
                if(e.info!=null&&e.info.resizeMode!=AppWidgetProviderInfo.RESIZE_NONE){TextView resize=Ui.text(activity,"◢",24,Ui.ACCENT);resize.setGravity(Gravity.CENTER);resize.setBackgroundColor(Ui.PANEL);resize.setContentDescription("ウィジェットのサイズ変更");e.frame.addView(resize,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.BOTTOM|Gravity.RIGHT));gesture(resize,e,true);}
                else if(e.info!=null){TextView fixed=Ui.text(activity,"固定サイズ",11,Ui.MUTED);fixed.setGravity(Gravity.CENTER);fixed.setContentDescription("提供元がサイズ変更に非対応");fixed.setOnClickListener(v->Ui.message(activity,"このウィジェットは提供元がサイズ変更に対応していません"));bar.addView(fixed,new LinearLayout.LayoutParams(dp(76),-1));}
            }
            canvas.addView(e.frame,new FrameLayout.LayoutParams(dp(e.w),dp(e.h)));layout(e);
        }
    }
    private void relayout(){for(Entry e:entries)layout(e);}
    private void layout(Entry e){
        int availableW=pxToDp(canvas.getWidth()),availableH=pxToDp(canvas.getHeight());if(availableW<=0||availableH<=0)return;
        int w=Math.min(e.w,availableW),h=Math.min(e.h,availableH),caption=editing?32:0;
        int contentY=Math.max(0,Math.min(e.y,availableH-h));
        // The saved rectangle describes provider content, never the editing toolbar.
        // Usually the toolbar sits above that rectangle. At the top edge temporarily
        // shift content down; do not silently shrink or save a changed widget size.
        int frameY=Math.max(0,contentY-caption);
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(w),dp(h+caption));p.leftMargin=dp(Math.max(0,Math.min(e.x,availableW-w)));p.topMargin=dp(frameY);e.frame.setLayoutParams(p);
        View content=e.frame.getChildAt(0);FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,dp(h));cp.topMargin=dp(caption);content.setLayoutParams(cp);
        if(e.view!=null)e.view.updateAppWidgetSize(null,w,h,w,h);
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void gesture(View handle,Entry e,boolean resize){
        handle.setOnTouchListener(new View.OnTouchListener(){float x,y;int ox,oy,ow,oh;boolean changed;
            public boolean onTouch(View v,android.view.MotionEvent event){
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN){x=event.getRawX();y=event.getRawY();FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)e.frame.getLayoutParams();ox=pxToDp(p.leftMargin);oy=pxToDp(p.topMargin)+(editing?32:0);ow=pxToDp(p.width);oh=pxToDp(p.height)-(editing?32:0);changed=false;return true;}
                if(event.getActionMasked()==MotionEvent.ACTION_MOVE){
                    int dx=pxToDp(Math.round(event.getRawX()-x)),dy=pxToDp(Math.round(event.getRawY()-y));int aw=pxToDp(canvas.getWidth()),ah=pxToDp(canvas.getHeight());
                    if(resize){int mode=e.info.resizeMode;
                        if((mode&AppWidgetProviderInfo.RESIZE_HORIZONTAL)!=0)e.w=Math.min(Math.max(1,aw-ox),Math.max(Math.max(80,pxToDp(e.info.minResizeWidth)),ow+dx));
                        if((mode&AppWidgetProviderInfo.RESIZE_VERTICAL)!=0)e.h=Math.min(Math.max(1,ah-oy),Math.max(Math.max(60,pxToDp(e.info.minResizeHeight)),oh+dy));
                    }else{e.x=Math.max(0,Math.min(ox+dx,aw-ow));e.y=Math.max(0,Math.min(oy+dy,ah-oh));}
                    changed=true;layout(e);return true;
                }
                if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){if(changed)save();return true;}return true;
            }
        });
    }
}
