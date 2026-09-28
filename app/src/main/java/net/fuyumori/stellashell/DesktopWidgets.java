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
        int id,x,y,w,h,mode,baseW,baseH;
        WidgetViewport viewport;
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
                JSONObject j=array.getJSONObject(i);Entry e=new Entry();e.id=j.getInt("id");e.x=j.optInt("x",24);e.y=j.optInt("y",140);e.w=Math.max(80,j.optInt("w",320));e.h=Math.max(60,j.optInt("h",180));e.mode=WidgetGeometry.mode(j.optInt("mode",0));e.baseW=j.optInt("baseW",0);e.baseH=j.optInt("baseH",0);entries.add(e);
            }
        }catch(JSONException e){Ui.message(a,activity.getString(R.string.ui_could_not_load_saved_widgets));}
        // Reconcile only this host's unreferenced allocations, never other launchers' widgets.
        Set<Integer> retained=new HashSet<>();for(Entry e:entries)retained.add(e.id);if(pending>=0)retained.add(pending);
        for(int id:host.getAppWidgetIds())if(!retained.contains(id))host.deleteAppWidgetId(id);
        canvas.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)relayout();});
        rebuild();
    }
    void start(){try{host.startListening();}catch(RuntimeException e){Ui.message(activity,activity.getString(R.string.ui_widget_update)+e.getMessage());}}
    void stop(){host.stopListening();}
    void destroy(){if(activity.isFinishing()&&pending>=0)cancel();}
    boolean isEditing(){return editing;}
    void setEditing(boolean value){editing=value;rebuild();}
    void choose(){
        if(pending>=0){new AlertDialog.Builder(activity).setMessage(activity.getString(R.string.ui_a_widget_is_still_being_added_cancel_it_and_choose_another)).setNegativeButton(activity.getString(R.string.ui_back),null).setPositiveButton(activity.getString(R.string.ui_choose_again),(d,w)->{cancel();choose();}).show();return;}
        List<AppWidgetProviderInfo> providers=new ArrayList<>(manager.getInstalledProviders());
        providers.removeIf(p->(p.widgetCategory&AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)==0);
        providers.sort(Comparator.comparing(p->p.loadLabel(activity.getPackageManager()),String.CASE_INSENSITIVE_ORDER));
        String[] labels=new String[providers.size()];for(int i=0;i<labels.length;i++){AppWidgetProviderInfo p=providers.get(i);labels[i]=p.loadLabel(activity.getPackageManager())+"\n"+p.provider.getPackageName();}
        if(labels.length==0){Ui.message(activity,activity.getString(R.string.ui_no_widgets_available));return;}
        new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.ui_add_widget)).setItems(labels,(d,n)->allocate(providers.get(n))).setNegativeButton(activity.getString(R.string.ui_cancel),null).show();
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
        }catch(RuntimeException e){cancel();Ui.message(activity,activity.getString(R.string.ui_could_not_add_widget)+e.getMessage());}
    }
    boolean result(int request,int result){
        if(request!=BIND&&request!=CONFIGURE)return false;
        if(pending<0)return true;
        if(result!=Activity.RESULT_OK){cancel();return true;}
        if(request==BIND)configure();else finishAdd();return true;
    }
    private void configure(){
        AppWidgetProviderInfo info=manager.getAppWidgetInfo(pending);
        if(info==null){cancel();Ui.message(activity,activity.getString(R.string.ui_widget_binding_did_not_complete));return;}
        if(info.configure!=null){
            try{host.startAppWidgetConfigureActivityForResult(activity,pending,0,CONFIGURE,null);}
            catch(RuntimeException e){cancel();Ui.message(activity,activity.getString(R.string.ui_could_not_open_configuration)+e.getMessage());}
        }else finishAdd();
    }
    private void finishAdd(){
        AppWidgetProviderInfo info=manager.getAppWidgetInfo(pending);if(info==null){cancel();return;}
        Entry e=new Entry();e.id=pending;e.x=24+entries.size()%5*24;e.y=140+entries.size()%5*24;
        e.w=defaultSize(info,true);e.h=defaultSize(info,false);entries.add(e);
        // Commit the entry and clear pending together: recreation cannot orphan a bound ID.
        pending=-1;save();editing=true;rebuild();Ui.message(activity,info.resizeMode==AppWidgetProviderInfo.RESIZE_NONE?activity.getString(R.string.ui_drag_the_top_bar_to_move_the_provider_marks_this_widget_as_fixed):activity.getString(R.string.ui_drag_the_top_bar_to_move_or_the_bottom_right_corner_to_resize_fin));
    }
    private void cancel(){if(pending>=0)host.deleteAppWidgetId(pending);pending=-1;prefs.edit().remove("pending").apply();}
    private int pxToDp(int value){return Math.round(value/activity.getResources().getDisplayMetrics().density);}
    private int dp(int n){return Ui.dp(activity,n);}
    private void save(){
        JSONArray array=new JSONArray();for(Entry e:entries)try{array.put(new JSONObject().put("id",e.id).put("x",e.x).put("y",e.y).put("w",e.w).put("h",e.h).put("mode",e.mode).put("baseW",e.baseW).put("baseH",e.baseH));}catch(JSONException ignored){}
        prefs.edit().putString("items",array.toString()).putInt("pending",pending).apply();
    }
    private void rebuild(){
        canvas.removeAllViews();for(Entry e:entries){
            e.info=manager.getAppWidgetInfo(e.id);e.frame=new FrameLayout(activity);
            if(e.info!=null){
                e.view=host.createView(activity,e.id,e.info);e.viewport=new WidgetViewport(activity);e.viewport.addView(e.view);e.frame.addView(e.viewport,new FrameLayout.LayoutParams(-1,-1));
            }else{e.view=null;TextView missing=Ui.text(activity,activity.getString(R.string.ui_widget_unavailable_remove_it_in_edit_mode),14,Ui.MUTED);e.frame.addView(missing);}
            if(editing){
                e.frame.setBackground(Ui.rounded(activity,Ui.PANEL,8));
                // Editing intercepts provider touches; normal mode leaves all gestures to RemoteViews.
                View shield=new View(activity);shield.setClickable(true);e.frame.addView(shield,new FrameLayout.LayoutParams(-1,-1));
                LinearLayout bar=new LinearLayout(activity);bar.setBackgroundColor(Ui.PANEL);
                TextView move=Ui.text(activity,activity.getString(R.string.ui_move)+(e.info==null?activity.getString(R.string.ui_widget):e.info.loadLabel(activity.getPackageManager())),13,Ui.TEXT);move.setGravity(Gravity.CENTER_VERTICAL);move.setPadding(dp(8),0,0,0);move.setContentDescription(activity.getString(R.string.ui_move_widget));bar.addView(move,new LinearLayout.LayoutParams(0,-1,1));gesture(move,e,false);
                if(e.info!=null){TextView settings=Ui.text(activity,"⋮",22,Ui.ACCENT);settings.setGravity(Gravity.CENTER);settings.setContentDescription(activity.getString(R.string.ui_widget_display_settings));settings.setOnClickListener(v->options(e));bar.addView(settings,new LinearLayout.LayoutParams(dp(36),-1));}
                TextView remove=Ui.text(activity,"×",22,Ui.TEXT);remove.setGravity(Gravity.CENTER);remove.setContentDescription(activity.getString(R.string.ui_remove_widget));bar.addView(remove,new LinearLayout.LayoutParams(dp(40),-1));remove.setOnClickListener(v->new AlertDialog.Builder(activity).setMessage(activity.getString(R.string.ui_remove_this_widget)).setNegativeButton(activity.getString(R.string.ui_cancel),null).setPositiveButton(activity.getString(R.string.ui_remove),(d,w)->{entries.remove(e);save();host.deleteAppWidgetId(e.id);rebuild();}).show());
                e.frame.addView(bar,new FrameLayout.LayoutParams(-1,dp(32),Gravity.TOP));
                if(e.info!=null&&(e.mode!=WidgetGeometry.NORMAL||e.info.resizeMode!=AppWidgetProviderInfo.RESIZE_NONE)){TextView resize=Ui.text(activity,"◢",24,Ui.ACCENT);resize.setGravity(Gravity.CENTER);resize.setBackgroundColor(Ui.PANEL);resize.setContentDescription(activity.getString(R.string.ui_resize_widget));e.frame.addView(resize,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.BOTTOM|Gravity.RIGHT));gesture(resize,e,true);}
                else if(e.info!=null){TextView fixed=Ui.text(activity,activity.getString(R.string.ui_fixed_size),11,Ui.MUTED);fixed.setGravity(Gravity.CENTER);fixed.setContentDescription(activity.getString(R.string.ui_the_provider_does_not_support_resizing));fixed.setOnClickListener(v->options(e));bar.addView(fixed,new LinearLayout.LayoutParams(dp(76),-1));}
            }
            canvas.addView(e.frame,new FrameLayout.LayoutParams(dp(e.w),dp(e.h)));layout(e);
        }
    }
    private int padding(AppWidgetProviderInfo info,boolean horizontal){
        android.graphics.Rect p=AppWidgetHostView.getDefaultPaddingForWidget(activity,info.provider,null);
        return (int)Math.ceil((horizontal?p.left+p.right:p.top+p.bottom)/activity.getResources().getDisplayMetrics().density);
    }
    private int defaultSize(AppWidgetProviderInfo info,boolean horizontal){
        return Math.max(horizontal?240:120,pxToDp(horizontal?info.minWidth:info.minHeight)+padding(info,horizontal));
    }
    private int minimum(Entry e,boolean horizontal){
        if(e.mode!=WidgetGeometry.NORMAL)return horizontal?80:60;
        int raw=horizontal?e.info.minResizeWidth:e.info.minResizeHeight;
        if(raw<=0)raw=horizontal?e.info.minWidth:e.info.minHeight;
        return Math.max(horizontal?80:60,pxToDp(raw)+padding(e.info,horizontal));
    }
    private int maximum(Entry e,boolean horizontal){
        if(e.mode!=WidgetGeometry.NORMAL||android.os.Build.VERSION.SDK_INT<31)return 0;
        int raw=horizontal?e.info.maxResizeWidth:e.info.maxResizeHeight;
        return raw>0?pxToDp(raw)+padding(e.info,horizontal):0;
    }
    private void options(Entry e){
        String[] names={activity.getString(R.string.ui_normal_respect_provider_limits),activity.getString(R.string.ui_force_resize_adjust_width_and_height),activity.getString(R.string.ui_scale_proportionally_may_add_margins)};
        new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.ui_widget_display_settings))
            .setSingleChoiceItems(names,e.mode,(dialog,which)->{
                e.mode=which;
                if(which==WidgetGeometry.SCALE&&(e.baseW<=0||e.baseH<=0)){e.baseW=defaultSize(e.info,true);e.baseH=defaultSize(e.info,false);}
                save();dialog.dismiss();rebuild();
                Ui.message(activity,which==WidgetGeometry.FORCE?activity.getString(R.string.ui_drag_the_bottom_right_corner_to_resize_content_reflow_depends_on):which==WidgetGeometry.SCALE?activity.getString(R.string.ui_drag_the_bottom_right_corner_to_scale_use_render_size_to_adjust_t):activity.getString(R.string.ui_provider_size_limits_are_now_applied));
            }).setNeutralButton(activity.getString(R.string.ui_render_size),(d,w)->renderSize(e))
            .setPositiveButton(activity.getString(R.string.ui_reset_to_recommended_size),(d,w)->{e.w=defaultSize(e.info,true);e.h=defaultSize(e.info,false);e.baseW=e.w;e.baseH=e.h;save();rebuild();})
            .setNegativeButton(activity.getString(R.string.ui_close),null).show();
    }
    private void renderSize(Entry e){
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(20),dp(12),dp(20),0);
        panel.addView(Ui.text(activity,activity.getString(R.string.ui_size_before_scaling_dp_including_padding_increase_it_if_content_i),14,Ui.TEXT));
        EditText width=new EditText(activity),height=new EditText(activity);
        width.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);height.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        width.setHint(activity.getString(R.string.ui_width_dp));height.setHint(activity.getString(R.string.ui_height_dp));
        width.setText(String.valueOf(e.baseW>0?e.baseW:defaultSize(e.info,true)));height.setText(String.valueOf(e.baseH>0?e.baseH:defaultSize(e.info,false)));
        panel.addView(width);panel.addView(height);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.ui_size_before_scaling)).setView(panel).setNegativeButton(activity.getString(R.string.ui_cancel),null).setPositiveButton(activity.getString(R.string.ui_apply),null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{int w=Integer.parseInt(width.getText().toString()),h=Integer.parseInt(height.getText().toString());
                if(w<80||h<60||w>2048||h>2048)throw new NumberFormatException();
                e.baseW=w;e.baseH=h;e.mode=WidgetGeometry.SCALE;save();dialog.dismiss();rebuild();
            }catch(NumberFormatException ex){Ui.message(activity,activity.getString(R.string.ui_enter_width_80_2048_and_height_60_2048_dp));}
        }));dialog.show();
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
        if(e.view!=null){
            int logicalW=w,logicalH=h;
            if(e.mode==WidgetGeometry.SCALE){logicalW=e.baseW>0?e.baseW:defaultSize(e.info,true);logicalH=e.baseH>0?e.baseH:defaultSize(e.info,false);}
            e.viewport.contentSize(dp(logicalW),dp(logicalH));
            if(android.os.Build.VERSION.SDK_INT>=31)e.view.updateAppWidgetSize(new Bundle(),Collections.singletonList(new android.util.SizeF(logicalW,logicalH)));
            else e.view.updateAppWidgetSize(null,logicalW,logicalH,logicalW,logicalH);
        }
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void gesture(View handle,Entry e,boolean resize){
        handle.setOnTouchListener(new View.OnTouchListener(){float x,y;int ox,oy,ow,oh;boolean changed;
            public boolean onTouch(View v,android.view.MotionEvent event){
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN){x=event.getRawX();y=event.getRawY();FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)e.frame.getLayoutParams();ox=pxToDp(p.leftMargin);oy=pxToDp(p.topMargin)+(editing?32:0);ow=pxToDp(p.width);oh=pxToDp(p.height)-(editing?32:0);changed=false;return true;}
                if(event.getActionMasked()==MotionEvent.ACTION_MOVE){
                    int dx=pxToDp(Math.round(event.getRawX()-x)),dy=pxToDp(Math.round(event.getRawY()-y));int aw=pxToDp(canvas.getWidth()),ah=pxToDp(canvas.getHeight());
                    if(resize){int mode=e.mode==WidgetGeometry.NORMAL?e.info.resizeMode:AppWidgetProviderInfo.RESIZE_BOTH;
                        if((mode&AppWidgetProviderInfo.RESIZE_HORIZONTAL)!=0)e.w=WidgetGeometry.resize(ow+dx,aw-ox,minimum(e,true),maximum(e,true));
                        if((mode&AppWidgetProviderInfo.RESIZE_VERTICAL)!=0)e.h=WidgetGeometry.resize(oh+dy,ah-oy,minimum(e,false),maximum(e,false));
                    }else{e.x=Math.max(0,Math.min(ox+dx,aw-ow));e.y=Math.max(0,Math.min(oy+dy,ah-oh));}
                    changed=true;layout(e);return true;
                }
                if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){if(changed)save();return true;}return true;
            }
        });
    }
}
