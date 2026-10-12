package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.layout.WidgetGeometry;
import net.fuyumori.stellashell.core.layout.WidgetLayoutProfiles;
import net.fuyumori.stellashell.core.layout.WidgetPages;
import net.fuyumori.stellashell.core.layout.WidgetSidePlacement;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Standard Android widgets. Binding/configuration remain system/provider-owned flows. */
final class DesktopWidgets {
    private static final int HOST=0x534f47, BIND=7101, CONFIGURE=7102, IMAGE=7103;
    private final Activity activity;
    private final FrameLayout canvas;
    private final AppWidgetManager manager;
    private final AppWidgetHost host;
    private final android.content.SharedPreferences prefs;
    private final List<Entry> entries=new ArrayList<>();
    private static final String LAYOUTS="layout_profiles_v1";
    private WidgetLayoutProfiles layouts;
    private WidgetLayoutProfiles.Profile profile;
    private WidgetPages pages;
    private Runnable pagesChanged=()->{};
    private final View.OnLayoutChangeListener layoutChanged=(v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)relayout();};
    private boolean editing,editorLeft,editorCollapsed,hubFromRight;
    private WidgetDetails details;
    private Entry selected;
    private View editor;
    private TextView overlapHint;
    private final Rect reservedControls=new Rect();
    private Runnable editingChanged=()->{};
    private final TextView[] values=new TextView[4];
    private int step=8;
    private int pending=-1;
    private boolean destroyed;
    private AlertDialog contentDialog;
    private AlertDialog geometryDialog;
    private final java.util.concurrent.ExecutorService importer=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final class Entry {
        int id,x,y,w,h,mode,baseW,baseH;
        String kind="widget",text="",image="";
        int background=0xff1c2636,opacity=0,textColor=0xffe7edf5,textSize=20,align=0;
        WidgetViewport viewport;
        FrameLayout frame;
        AppWidgetHostView view;
        AppWidgetProviderInfo info;
    }
    DesktopWidgets(Activity a,FrameLayout c){this(a,c,false);}
    DesktopWidgets(Activity a,FrameLayout c,boolean panel){this(a,c,panel,false);}
    DesktopWidgets(Activity a,FrameLayout c,boolean panel,boolean home){
        this(a,c,panel?(WorkspaceProfile.phone(a)?"phone_panel_widgets":"panel_widgets"):home?"home_widgets":"desktop_widgets",panel?(WorkspaceProfile.phone(a)?HOST+3:HOST+1):home?HOST+2:HOST);
    }
    DesktopWidgets(Activity a,FrameLayout c,String preferenceName,int hostId){
        activity=a;canvas=c;manager=AppWidgetManager.getInstance(a);host=new AppWidgetHost(a,hostId);
        prefs=a.getSharedPreferences(preferenceName,0);pending=prefs.getInt("pending",-1);
        try{
            JSONArray array=new JSONArray(prefs.getString("items","[]"));
            for(int i=0;i<array.length();i++){
                JSONObject j=array.getJSONObject(i);Entry e=new Entry();e.id=j.getInt("id");e.x=j.optInt("x",24);e.y=j.optInt("y",140);e.w=Math.max(80,j.optInt("w",320));e.h=Math.max(60,j.optInt("h",180));e.mode=WidgetGeometry.mode(j.optInt("mode",0));e.baseW=j.optInt("baseW",0);e.baseH=j.optInt("baseH",0);
                e.kind=j.optString("kind","widget");if(!e.kind.equals("text")&&!e.kind.equals("image"))e.kind="widget";
                e.text=j.optString("text","");e.image=j.optString("image","");e.background=j.optInt("background",e.background);e.opacity=Math.max(0,Math.min(100,j.optInt("opacity",0)));
                e.textColor=j.optInt("textColor",e.textColor);e.textSize=Math.max(10,Math.min(128,j.optInt("textSize",20)));e.align=Math.max(0,Math.min(2,j.optInt("align",0)));entries.add(e);
            }
        }catch(JSONException e){Ui.message(a,activity.getString(R.string.ui_could_not_load_saved_widgets));}
        profile=currentProfile();
        String savedLayouts=prefs.getString(LAYOUTS,null);
        layouts=new WidgetLayoutProfiles(savedLayouts,geometrySnapshot());
        // Seed every profile before applying any profile-specific edit. IDs/content stay shared.
        String initializedLayouts=layouts.encode();
        if(!initializedLayouts.equals(savedLayouts))prefs.edit().putString(LAYOUTS,initializedLayouts).apply();
        applyProfile();
        // Reconcile only this host's unreferenced allocations, never other launchers' widgets.
        Set<Integer> retained=new HashSet<>();for(Entry e:entries)if(e.kind.equals("widget"))retained.add(e.id);if(pending>=0)retained.add(pending);
        for(int id:host.getAppWidgetIds())if(!retained.contains(id))host.deleteAppWidgetId(id);
        canvas.addOnLayoutChangeListener(layoutChanged);
        // Empty-surface holds must not enter widget editing during window gestures.
        rebuild();
    }
    void enablePages(Runnable changed){pagesChanged=changed;loadPages();applyPages();}
    int pageCount(){return pages==null?1:pages.count();}
    int currentPage(){return pages==null?0:pages.current();}
    boolean emptyPage(){return pages!=null&&pages.empty();}
    private List<Integer> widgetIds(){List<Integer> ids=new ArrayList<>();for(Entry e:entries)ids.add(e.id);return ids;}
    private String pagesKey(){return "pages_v1_"+profile.key;}
    private void loadPages(){pages=new WidgetPages(prefs.getString(pagesKey(),null),widgetIds());}
    private void savePages(){
        if(pages==null)return;
        List<Integer> ids=widgetIds();pages.reconcile(ids,pages.current());
        android.content.SharedPreferences.Editor edit=prefs.edit();boolean changed=false;
        for(WidgetLayoutProfiles.Profile target:WidgetLayoutProfiles.Profile.values()){
            String key="pages_v1_"+target.key,old=prefs.getString(key,null);
            if(target!=profile&&old==null)continue;
            String value=(target==profile?pages:new WidgetPages(old,ids)).encode();
            if(!value.equals(old)){edit.putString(key,value);changed=true;}
        }
        if(changed)edit.apply();
    }
    private boolean onPage(Entry e){return pages==null||pages.page(e.id)==pages.current();}
    private List<Entry> visibleEntries(){List<Entry> out=new ArrayList<>();for(Entry e:entries)if(onPage(e))out.add(e);return out;}
    private void applyPages(){for(Entry e:entries)if(e.frame!=null)e.frame.setVisibility(onPage(e)?View.VISIBLE:View.GONE);pagesChanged.run();}
    void selectPage(int page){
        if(pages==null||page<0||page>=pages.count()||page==pages.current())return;
        finishEditing();cancelGeometryInteraction();pages.select(page);savePages();applyPages();
    }
    void addPage(){if(pages==null||pages.count()==WidgetPages.LIMIT)return;finishEditing();cancelGeometryInteraction();pages.add();savePages();applyPages();}
    void removeEmptyPage(){if(pages==null||!pages.empty()||pages.count()==1)return;finishEditing();pages.removeEmpty();savePages();applyPages();}
    void moveWidgetToPage(int id,int page){
        if(pages==null||!pages.move(id,page))return;
        cancelGeometryInteraction();savePages();selected=null;finishEditing();applyPages();
    }
    private void moveToPage(Entry entry){
        String[] labels=new String[pageCount()];for(int i=0;i<labels.length;i++)labels[i]=activity.getString(R.string.widget_page_number,i+1);
        DesktopBackdrop.showDialog(activity,new AlertDialog.Builder(activity).setTitle(R.string.widget_move_page).setSingleChoiceItems(labels,currentPage(),(dialog,index)->{
            moveWidgetToPage(entry.id,index);dialog.dismiss();
        }).setNegativeButton(R.string.ui_cancel,null).create());
    }
    void start(){try{host.startListening();}catch(RuntimeException e){Ui.message(activity,activity.getString(R.string.ui_widget_update)+e.getMessage());}}
    void stop(){host.stopListening();}
    void configurationChanged(){
        // Pixel bounds may stay unchanged on a density-only display update.
        // Recreate content too: text sizes, provider resources and edit chrome
        // were resolved against the previous Resources configuration.
        cancelGeometryInteraction();
        switchProfile();
        rebuild();
    }
    void destroy(){closeDetails();destroyed=true;canvas.removeOnLayoutChangeListener(layoutChanged);importer.shutdownNow();if(contentDialog!=null)contentDialog.dismiss();if(geometryDialog!=null)geometryDialog.dismiss();if(activity.isFinishing()&&pending>=0)cancel();}
    boolean isEditing(){return editing;}
    void onEditingChanged(Runnable listener){editingChanged=listener;}
    boolean finishEditing(){if(!editing)return false;setEditing(false);return true;}
    void setEditing(boolean value){
        if(!value)closeDetails();editing=value;List<Entry> shown=visibleEntries();if(value&&selected==null&&!shown.isEmpty())selected=shown.get(shown.size()-1);
        if(!value){for(Entry e:entries)((WidgetEditFrame)e.frame).cancelEditingGesture();save();selected=null;}
        editorCollapsed=false;updateSelection();showEditor();editingChanged.run();
    }
    private void select(Entry entry){closeDetails();boolean entering=!editing;boolean different=selected!=entry;selected=entry;editing=true;
        if(entering){editorCollapsed=false;editingChanged.run();}
        updateSelection();if(entering||different)showEditor();
    }
    private void updateSelection(){for(Entry e:entries){
        android.graphics.drawable.GradientDrawable outline=new android.graphics.drawable.GradientDrawable();
        outline.setColor(android.graphics.Color.TRANSPARENT);outline.setStroke(dp(e==selected?2:1),e==selected?Ui.ACCENT:Ui.MUTED);
        e.frame.setForeground(editing?outline:null);
    }}
    void choose(){
        if(pending>=0){DesktopBackdrop.showDialog(activity,new AlertDialog.Builder(activity).setMessage(activity.getString(R.string.ui_a_widget_is_still_being_added_cancel_it_and_choose_another)).setNegativeButton(activity.getString(R.string.ui_back),null).setPositiveButton(activity.getString(R.string.ui_choose_again),(d,w)->{cancel();choose();}).create());return;}
        List<AppWidgetProviderInfo> providers=new ArrayList<>(manager.getInstalledProviders());
        providers.removeIf(p->(p.widgetCategory&AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)==0);
        providers.sort(Comparator.comparing(p->p.loadLabel(activity.getPackageManager()),String.CASE_INSENSITIVE_ORDER));
        if(providers.isEmpty()){Ui.message(activity,activity.getString(R.string.ui_no_widgets_available));return;}
        List<String> labels=new ArrayList<>(),index=new ArrayList<>();
        for(AppWidgetProviderInfo provider:providers){
            String widget=provider.loadLabel(activity.getPackageManager()),app=provider.provider.getPackageName();
            try{app=String.valueOf(activity.getPackageManager().getApplicationLabel(activity.getPackageManager().getApplicationInfo(provider.provider.getPackageName(),0)));}
            catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
            labels.add(widget+"\n"+app+" · "+provider.provider.getPackageName());
            index.add(searchKey(widget+" "+app+" "+provider.provider.getPackageName()));
        }
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(16),0,dp(16),0);
        LinearLayout searchRow=new LinearLayout(activity);
        EditText search=new EditText(activity);search.setSingleLine(true);search.setHint(R.string.widget_search_hint);search.setContentDescription(activity.getString(R.string.widget_search_hint));
        searchRow.addView(search,new LinearLayout.LayoutParams(0,dp(48),1));
        TextView clear=Ui.text(activity,"×",24,Ui.TEXT);clear.setGravity(Gravity.CENTER);clear.setContentDescription(activity.getString(R.string.widget_search_clear));clear.setOnClickListener(v->search.setText(""));
        searchRow.addView(clear,new LinearLayout.LayoutParams(dp(48),dp(48)));panel.addView(searchRow);
        TextView empty=Ui.text(activity,activity.getString(R.string.widget_search_empty),14,Ui.MUTED);empty.setPadding(0,dp(12),0,dp(12));panel.addView(empty);
        ListView list=new ListView(activity);
        panel.addView(list,new LinearLayout.LayoutParams(-1,Math.max(dp(100),Math.min(dp(360),activity.getResources().getDisplayMetrics().heightPixels/2))));
        List<AppWidgetProviderInfo> visible=new ArrayList<>();
        ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_list_item_1,new ArrayList<>());list.setAdapter(adapter);
        Runnable filter=()->{
            String query=searchKey(search.getText().toString()).trim();String[] terms=query.isEmpty()?new String[0]:query.split("\\s+");
            visible.clear();adapter.setNotifyOnChange(false);adapter.clear();
            for(int i=0;i<providers.size();i++){
                boolean match=true;for(String term:terms)if(!index.get(i).contains(term)){match=false;break;}
                if(match){visible.add(providers.get(i));adapter.add(labels.get(i));}
            }
            adapter.notifyDataSetChanged();list.setSelection(0);empty.setVisibility(visible.isEmpty()?View.VISIBLE:View.GONE);clear.setVisibility(query.isEmpty()?View.INVISIBLE:View.VISIBLE);
        };
        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){filter.run();}
            public void afterTextChanged(android.text.Editable text){}
        });
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.ui_add_widget)).setView(panel).setNegativeButton(activity.getString(R.string.ui_cancel),null).create();
        list.setOnItemClickListener((parent,view,position,id)->{AppWidgetProviderInfo selected=visible.get(position);dialog.dismiss();allocate(selected);});
        filter.run();dialog.setOnShowListener(d->dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE));DesktopBackdrop.showDialog(activity,dialog);

    }
    private static String searchKey(String value){return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    private void allocate(AppWidgetProviderInfo info){
        try{
            pending=host.allocateAppWidgetId();prefs.edit().putInt("pending",pending).apply();
            Bundle options=new Bundle();options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
            if(manager.bindAppWidgetIdIfAllowed(pending,info.getProfile(),info.provider,options))configure();
            else{
                Intent intent=new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,pending).putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,info.provider).putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE,info.getProfile()).putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS,options);
                DesktopBackdrop.startActivityForResult(activity,intent,BIND);
            }
        }catch(RuntimeException e){cancel();Ui.message(activity,activity.getString(R.string.ui_could_not_add_widget)+e.getMessage());}
    }
    boolean result(int request,int result){return result(request,result,null);}
    boolean result(int request,int result,Intent data){
        if(request==IMAGE){
            if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return true;
            android.net.Uri uri=data.getData();Ui.message(activity,activity.getString(R.string.widget_importing_image));
            importer.execute(()->{
                try{String image=WidgetImages.copy(activity,uri);activity.runOnUiThread(()->{
                    if(destroyed){WidgetImages.remove(activity,image);return;}Entry e=custom("image");e.image=image;addCustom(e);
                });}catch(java.io.IOException|RuntimeException error){activity.runOnUiThread(()->{if(!destroyed)Ui.message(activity,activity.getString(R.string.widget_image_failed));});}
            });return true;
        }
        if(request!=BIND&&request!=CONFIGURE)return false;
        if(pending<0)return true;
        if(result!=Activity.RESULT_OK){cancel();return true;}
        if(request==BIND)configure();else finishAdd();return true;
    }
    private void configure(){
        AppWidgetProviderInfo info=manager.getAppWidgetInfo(pending);
        if(info==null){cancel();Ui.message(activity,activity.getString(R.string.ui_widget_binding_did_not_complete));return;}
        if(info.configure!=null){
            try{
                ActivityOptions options=ActivityOptions.makeBasic();
                if(activity.getDisplay()!=null)options.setLaunchDisplayId(activity.getDisplay().getDisplayId());
                // User initiated widget setup sends a system-owned PendingIntent.
                // Android 14 requires the sender to opt in to passing its launch privilege.
                if(android.os.Build.VERSION.SDK_INT>=34)
                    options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                DesktopBackdrop.configureWidget(activity,host,pending,CONFIGURE,options.toBundle());
            }
            catch(RuntimeException e){cancel();Ui.message(activity,activity.getString(R.string.ui_could_not_open_configuration)+e.getMessage());}
        }else finishAdd();
    }
    private void finishAdd(){
        AppWidgetProviderInfo info=manager.getAppWidgetInfo(pending);if(info==null){cancel();return;}
        Entry e=new Entry();e.id=pending;e.x=24+entries.size()%5*24;e.y=140+entries.size()%5*24;
        e.w=defaultSize(info,true);e.h=defaultSize(info,false);entries.add(e);
        // Commit the entry and clear pending together: recreation cannot orphan a bound ID.
        pending=-1;save();selected=e;editing=true;editorCollapsed=false;rebuild();editingChanged.run();
        Ui.message(activity,activity.getString(R.string.widget_edit_hint));
    }
    private void cancel(){if(pending>=0)host.deleteAppWidgetId(pending);pending=-1;prefs.edit().remove("pending").apply();}
    private int pxToDp(int value){return Math.round(value/activity.getResources().getDisplayMetrics().density);}
    private int dp(int n){return Ui.dp(activity,n);}
    private Entry custom(String kind){
        Entry e=new Entry();e.kind=kind;int id=prefs.getInt("next_custom_id",-1);for(Entry item:entries)id=Math.min(id,item.id-1);e.id=id;prefs.edit().putInt("next_custom_id",id-1).apply();
        e.x=24;e.y=24;e.w=320;e.h=kind.equals("text")?120:240;e.textColor=Ui.TEXT;return e;
    }
    private void addCustom(Entry e){entries.add(e);selected=e;editing=true;editorCollapsed=false;save();rebuild();editingChanged.run();}
    void addText(){textDialog(custom("text"),true);}
    void addImage(){
        try{DesktopBackdrop.startActivityForResult(activity,new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),IMAGE);}
        catch(RuntimeException e){Ui.message(activity,activity.getString(R.string.widget_image_failed));}
    }
    private void textDialog(Entry e,boolean adding){
        LinearLayout form=Ui.column(activity);form.setPadding(dp(20),dp(12),dp(20),0);
        EditText text=new EditText(activity);text.setMinLines(3);text.setMaxLines(6);text.setGravity(Gravity.TOP);text.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);text.setText(e.text);text.setHint(R.string.widget_text);text.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});form.addView(text);
        form.addView(Ui.text(activity,activity.getString(R.string.widget_text_size),14,Ui.TEXT));EditText size=new EditText(activity);size.setInputType(2);size.setText(String.valueOf(e.textSize));form.addView(size);
        form.addView(Ui.text(activity,activity.getString(R.string.widget_text_color)+" (#RRGGBB)",14,Ui.TEXT));EditText color=new EditText(activity);color.setSingleLine();color.setText(String.format(Locale.ROOT,"#%06X",e.textColor&0xffffff));form.addView(color);
        Spinner align=new Spinner(activity);align.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{activity.getString(R.string.widget_align_left),activity.getString(R.string.widget_align_center),activity.getString(R.string.widget_align_right)}));align.setSelection(e.align);form.addView(align);
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(adding?R.string.widget_add_text:R.string.widget_edit_text).setView(scroll).setNegativeButton(R.string.ui_cancel,null).setPositiveButton(R.string.ui_save,null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            int n;try{n=Integer.parseInt(size.getText().toString());if(n<10||n>128)throw new NumberFormatException();}catch(NumberFormatException ex){size.setError(activity.getString(R.string.widget_text_size_range));return;}
            String hex=color.getText().toString().trim();if(!hex.matches("#[a-fA-F0-9]{6}")){color.setError(activity.getString(R.string.appearance_invalid_color));return;}
            if(text.getText().toString().trim().isEmpty()){text.setError(activity.getString(R.string.widget_text_empty));return;}
            e.text=text.getText().toString();e.textSize=n;e.textColor=android.graphics.Color.parseColor(hex);e.align=align.getSelectedItemPosition();if(adding)addCustom(e);else{save();rebuild();}dialog.dismiss();
        }));contentDialog=dialog;DesktopBackdrop.showDialog(activity,dialog);
    }
    private void save(){
        savePages();
        // Another live host instance may have saved a different profile in this same store.
        // Refresh inactive profiles, then replace only the geometry owned by this instance.
        layouts=new WidgetLayoutProfiles(prefs.getString(LAYOUTS,null),geometrySnapshot());
        layouts.capture(profile,geometrySnapshot());
        JSONArray array=new JSONArray();for(Entry e:entries)try{array.put(new JSONObject().put("id",e.id).put("x",e.x).put("y",e.y).put("w",e.w).put("h",e.h).put("mode",e.mode).put("baseW",e.baseW).put("baseH",e.baseH).put("kind",e.kind).put("text",e.text).put("image",e.image).put("background",e.background).put("opacity",e.opacity).put("textColor",e.textColor).put("textSize",e.textSize).put("align",e.align));}catch(JSONException ignored){}
        prefs.edit().putString("items",array.toString()).putString(LAYOUTS,layouts.encode()).putInt("pending",pending).apply();
    }
    private WidgetLayoutProfiles.Profile currentProfile(){
        return WidgetLayoutProfiles.resolve(WorkspaceProfile.phone(activity),activity.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE);
    }
    private Map<Integer,WidgetLayoutProfiles.Geometry> geometrySnapshot(){
        Map<Integer,WidgetLayoutProfiles.Geometry> snapshot=new LinkedHashMap<>();
        for(Entry e:entries)snapshot.put(e.id,new WidgetLayoutProfiles.Geometry(e.x,e.y,e.w,e.h,e.mode,e.baseW,e.baseH));
        return snapshot;
    }
    private void applyProfile(){
        Map<Integer,WidgetLayoutProfiles.Geometry> snapshot=layouts.snapshot(profile);
        for(Entry e:entries){WidgetLayoutProfiles.Geometry g=snapshot.get(e.id);if(g==null)continue;
            e.x=g.x;e.y=g.y;e.w=g.w;e.h=g.h;e.mode=g.mode;e.baseW=g.baseW;e.baseH=g.baseH;
        }
    }
    private void cancelGeometryInteraction(){
        closeDetails();
        for(Entry e:entries)if(e.frame instanceof WidgetEditFrame)((WidgetEditFrame)e.frame).cancelEditingGesture();
        if(geometryDialog!=null){geometryDialog.dismiss();geometryDialog=null;}
    }
    private boolean switchProfile(){
        WidgetLayoutProfiles.Profile next=currentProfile();if(next==profile)return false;
        // Cancel before capture so a half-completed pointer gesture cannot leak across layouts.
        cancelGeometryInteraction();save();profile=next;applyProfile();if(pages!=null){loadPages();selected=null;applyPages();}return true;
    }
    private void rebuild(){
        canvas.removeAllViews();editor=null;for(Entry e:entries){
            e.info=e.kind.equals("widget")?manager.getAppWidgetInfo(e.id):null;
            e.frame=new WidgetEditFrame(activity,new WidgetEditFrame.Actions(){
                int x,y,w,h,originalX,originalY,originalW,originalH;boolean resizing;
                public boolean editing(){return editing;}
                public boolean canResize(){return resizable(e,2)||resizable(e,3);}
                public void select(){DesktopWidgets.this.select(e);}
                public void begin(boolean resize){
                    if(editor!=null)editor.setVisibility(View.INVISIBLE);
                    resizing=resize;FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)e.frame.getLayoutParams();
                    x=pxToDp(p.leftMargin);y=pxToDp(p.topMargin);w=pxToDp(p.width);h=pxToDp(p.height);
                    originalX=e.x;originalY=e.y;originalW=e.w;originalH=e.h;
                }
                public void move(float dx,float dy){
                    int oldX=e.x,oldY=e.y,oldW=e.w,oldH=e.h;
                    long oldOverlap=controlsOverlap(e);
                    int aw=pxToDp(canvas.getWidth()),ah=pxToDp(canvas.getHeight()),deltaX=pxToDp(Math.round(dx)),deltaY=pxToDp(Math.round(dy));
                    if(resizing){
                        e.y=y;
                        if(resizable(e,2))e.w=WidgetGeometry.resize(w+deltaX,aw-x,e.info==null?80:minimum(e,true),e.info==null?0:maximum(e,true));
                        if(resizable(e,3))e.h=WidgetGeometry.resize(h+deltaY,ah-y,e.info==null?60:minimum(e,false),e.info==null?0:maximum(e,false));
                        e.x=sideX(x,e.w,aw);
                    }else{e.x=sideX(x+deltaX,e.w,aw);e.y=Math.max(0,Math.min(y+deltaY,ah-Math.min(e.h,ah)));}
                    if(controlsOverlap(e)>oldOverlap){e.x=oldX;e.y=oldY;e.w=oldW;e.h=oldH;}
                    layout(e);updateValues();
                }
                public void end(boolean cancel){if(editor!=null)editor.setVisibility(View.VISIBLE);if(cancel){e.x=originalX;e.y=originalY;e.w=originalW;e.h=originalH;layout(e);updateValues();}else save();}

            });
            e.frame.setContentDescription(activity.getString(R.string.ui_edit_widgets)+": "+label(e));
            e.frame.setBackground(Ui.rounded(activity,WidgetItemBackground.color(e.background,e.opacity),12));
            e.view=null;e.viewport=null;
            if(e.kind.equals("text")){
                TextView text=Ui.text(activity,e.text,e.textSize,e.textColor);text.setPadding(dp(12),dp(8),dp(12),dp(8));text.setGravity(Gravity.CENTER_VERTICAL|(e.align==0?Gravity.START:e.align==1?Gravity.CENTER_HORIZONTAL:Gravity.END));e.frame.addView(text,new FrameLayout.LayoutParams(-1,-1));
            }else if(e.kind.equals("image")){
                java.io.File file=WidgetImages.file(activity,e.image);
                if(file!=null&&file.isFile()){ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageURI(android.net.Uri.fromFile(file));image.setContentDescription(activity.getString(R.string.widget_image));e.frame.addView(image,new FrameLayout.LayoutParams(-1,-1));}
                else e.frame.addView(Ui.text(activity,activity.getString(R.string.widget_image_failed),14,Ui.MUTED));
            }else if(e.info!=null){
                e.view=host.createView(new WidgetLaunchContext(activity),e.id,e.info);e.viewport=new WidgetViewport(activity);e.viewport.addView(e.view);e.frame.addView(e.viewport,new FrameLayout.LayoutParams(-1,-1));
            }else{e.view=null;TextView missing=Ui.text(activity,activity.getString(R.string.ui_widget_unavailable_remove_it_in_edit_mode),14,Ui.MUTED);e.frame.addView(missing);}
            canvas.addView(e.frame,new FrameLayout.LayoutParams(dp(e.w),dp(e.h)));layout(e);
        }
        applyPages();updateSelection();showEditor();
    }
    private String label(Entry e){if(e.kind.equals("text"))return e.text.isEmpty()?activity.getString(R.string.widget_text):e.text.split("\\n",2)[0];if(e.kind.equals("image"))return activity.getString(R.string.widget_image);return e.info==null?activity.getString(R.string.ui_widget)+" #"+e.id:e.info.loadLabel(activity.getPackageManager());}
    private void showEditor(){
        if(editor!=null)canvas.removeView(editor);editor=null;overlapHint=null;if(!editing)return;
        if(editorCollapsed){
            Button tab=editorButton(editorLeft?"›":"‹",R.string.widget_edit_open,()->{editorCollapsed=false;showEditor();});
            editor=tab;canvas.addView(tab,new FrameLayout.LayoutParams(dp(48),dp(56),(editorLeft?Gravity.LEFT:Gravity.RIGHT)|Gravity.CENTER_VERTICAL));return;
        }
        LinearLayout card=Ui.column(activity);card.setPadding(dp(6),dp(4),dp(6),dp(4));
        card.setBackground(Ui.rounded(activity,Ui.BG,14));card.setElevation(dp(12));editor=card;
        List<Entry> shown=visibleEntries();
        LinearLayout heading=new LinearLayout(activity);
        if(!shown.isEmpty()){
            List<String> labels=new ArrayList<>();for(Entry e:shown)labels.add(label(e));
            Spinner picker=new Spinner(activity);picker.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,labels));
            picker.setSelection(Math.max(0,shown.indexOf(selected)));picker.setContentDescription(activity.getString(R.string.widget_edit_select));
            picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View view,int index,long id){if(editing&&editor==card&&index>=0&&index<shown.size()&&selected!=shown.get(index))select(shown.get(index));}});
            heading.addView(picker,new LinearLayout.LayoutParams(0,dp(48),1));
        }else heading.addView(Ui.text(activity,activity.getString(R.string.ui_add_widget),14,Ui.TEXT),new LinearLayout.LayoutParams(0,dp(48),1));
        heading.addView(editorButton("⇄",R.string.widget_edit_side,()->{editorLeft=!editorLeft;showEditor();}),new LinearLayout.LayoutParams(dp(48),dp(48)));card.addView(heading);
        TextView hint=Ui.text(activity,activity.getString(R.string.widget_direct_edit_hint),11,Ui.MUTED);hint.setMaxLines(2);card.addView(hint);
        overlapHint=Ui.text(activity,activity.getString(R.string.widget_controls_overlap),11,Ui.ACCENT);card.addView(overlapHint);updateOverlapHint();
        LinearLayout tools=new LinearLayout(activity);
        Button detail=editorButton(selected==null?"+":"⚙",selected==null?R.string.ui_add_widget:R.string.widget_details,()->{if(selected==null)choose();else showDetails();});
        tools.addView(detail,new LinearLayout.LayoutParams(0,dp(48),1));
        tools.addView(editorButton(editorLeft?"‹":"›",R.string.widget_edit_hide,()->{editorCollapsed=true;showEditor();}),new LinearLayout.LayoutParams(0,dp(48),1));
        tools.addView(editorButton("✓",R.string.widget_edit_done,()->setEditing(false)),new LinearLayout.LayoutParams(0,dp(48),1));card.addView(tools);
        canvas.addView(card,editorBounds());
    }
    /** Hub-only view transform. Reopening from another edge does not write a second layout. */
    void hubSide(boolean fromRight){
        if(hubFromRight!=fromRight){
            cancelGeometryInteraction();hubFromRight=fromRight;
            for(Entry e:entries)layout(e);
            updateValues();
        }
        editorLeft=!fromRight;if(editing)showEditor();
    }
    private int sideX(int coordinate,int width,int canvasWidth){return WidgetSidePlacement.x(coordinate,width,canvasWidth,hubFromRight);}
    private int screenX(Entry e){return sideX(e.x,e.w,pxToDp(canvas.getWidth()));}
    /** Coordinates are canvas pixels. Reservation never changes stored or displayed widget geometry. */
    void reservedControls(Rect area){
        if(reservedControls.equals(area))return;
        reservedControls.set(area);updateOverlapHint();
        if(editor!=null&&!editorCollapsed)editor.setLayoutParams(editorBounds());
    }
    private long controlsOverlap(Entry e){
        if(reservedControls.isEmpty())return 0;
        int aw=pxToDp(canvas.getWidth()),ah=pxToDp(canvas.getHeight());
        int w=Math.min(e.w,aw),h=Math.min(e.h,ah);
        int x=dp(sideX(e.x,w,aw)),y=dp(Math.max(0,Math.min(e.y,ah-h)));
        return (long)Math.max(0,Math.min(x+dp(w),reservedControls.right)-Math.max(x,reservedControls.left))
                *Math.max(0,Math.min(y+dp(h),reservedControls.bottom)-Math.max(y,reservedControls.top));
    }
    private void updateOverlapHint(){
        if(overlapHint==null)return;
        boolean overlap=false;for(Entry entry:visibleEntries())if(controlsOverlap(entry)>0){overlap=true;break;}
        overlapHint.setVisibility(overlap?View.VISIBLE:View.GONE);
    }
    private void closeDetails(){if(details!=null){WidgetDetails old=details;details=null;old.close();}Arrays.fill(values,null);}
    private void showDetails(){
        if(selected==null)return;closeDetails();Entry entry=selected;
        details=new WidgetDetails(activity,label(entry),tab->detailContent(entry,tab),()->{details=null;Arrays.fill(values,null);});
    }
    private View detailContent(Entry entry,int tab){
        Arrays.fill(values,null);LinearLayout panel=Ui.column(activity);panel.setPadding(dp(12),dp(8),dp(12),dp(8));
        if(tab==0){
            TextView hint=Ui.text(activity,activity.getString(R.string.widget_edit_hint),12,Ui.MUTED);panel.addView(hint);
            Button precision=Ui.button(activity,activity.getString(R.string.widget_edit_step,step),()->{step=step==1?8:1;if(details!=null)details.refresh();});panel.addView(precision);
            int[] names={R.string.widget_edit_x,R.string.widget_edit_y,R.string.widget_edit_width,R.string.widget_edit_height};
            for(int i=0;i<4;i++){
                final int axis=i;LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
                Button less=editorButton("−",names[i],()->adjust(entry,axis,geometry(entry,axis)-step));less.setContentDescription(activity.getString(names[i])+" −");
                Button more=editorButton("+",names[i],()->adjust(entry,axis,geometry(entry,axis)+step));more.setContentDescription(activity.getString(names[i])+" +");
                TextView value=Ui.text(activity,"",13,Ui.TEXT);value.setGravity(Gravity.CENTER);value.setBackground(Ui.toolbarBackground(activity,8));value.setOnClickListener(v->number(entry,axis,names[axis]));values[i]=value;
                row.addView(less,new LinearLayout.LayoutParams(dp(48),dp(48)));row.addView(value,new LinearLayout.LayoutParams(0,dp(48),1));row.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));panel.addView(row);
                boolean enabled=resizable(entry,axis);less.setEnabled(enabled);more.setEnabled(enabled);value.setEnabled(enabled);row.setAlpha(enabled?1:.45f);
            }
        }else if(tab==1){
            if(entry.info!=null){
                panel.addView(Ui.text(activity,activity.getString(R.string.ui_widget_display_settings),13,Ui.MUTED));
                RadioGroup modes=new RadioGroup(activity);String[] labels={activity.getString(R.string.widget_edit_normal),activity.getString(R.string.widget_edit_force),activity.getString(R.string.widget_edit_scale)};
                for(int i=0;i<labels.length;i++){RadioButton radio=new RadioButton(activity);radio.setText(labels[i]);radio.setTextColor(Ui.TEXT);radio.setId(View.generateViewId());modes.addView(radio,new RadioGroup.LayoutParams(-1,dp(48)));radio.setChecked(entry.mode==i);final int mode=i;radio.setOnClickListener(v->{entry.mode=mode;if(mode==WidgetGeometry.SCALE&&(entry.baseW<=0||entry.baseH<=0)){entry.baseW=defaultSize(entry.info,true);entry.baseH=defaultSize(entry.info,false);}save();layout(entry);if(details!=null)details.refresh();});}panel.addView(modes);
                if(entry.mode==WidgetGeometry.SCALE)panel.addView(Ui.button(activity,activity.getString(R.string.ui_render_size),()->renderSize(entry)));
                panel.addView(Ui.button(activity,activity.getString(R.string.ui_reset_to_recommended_size),()->{int left=screenX(entry);entry.w=defaultSize(entry.info,true);entry.h=defaultSize(entry.info,false);if(hubFromRight)entry.x=sideX(left,entry.w,pxToDp(canvas.getWidth()));entry.baseW=entry.w;entry.baseH=entry.h;layout(entry);save();updateValues();}));
            }
            panel.addView(Ui.button(activity,activity.getString(R.string.widget_item_background),()->contentDialog=WidgetItemBackground.show(activity,entry.background,entry.opacity,(color,opacity)->{entry.background=color;entry.opacity=opacity;save();entry.frame.setBackground(Ui.rounded(activity,WidgetItemBackground.color(color,opacity),12));})));
            if(entry.kind.equals("text"))panel.addView(Ui.button(activity,activity.getString(R.string.widget_edit_text),()->{closeDetails();textDialog(entry,false);}));
        }else{
            if(pages!=null&&pageCount()>1)panel.addView(Ui.button(activity,activity.getString(R.string.widget_move_page),()->{closeDetails();moveToPage(entry);}));
            Button remove=Ui.button(activity,activity.getString(R.string.ui_remove_widget),()->DesktopBackdrop.showDialog(activity,new AlertDialog.Builder(activity).setMessage(R.string.ui_remove_this_widget).setNegativeButton(R.string.ui_cancel,null).setPositiveButton(R.string.ui_remove,(dialog,which)->{
                closeDetails();entries.remove(entry);selected=null;save();
                if(entry.kind.equals("widget"))host.deleteAppWidgetId(entry.id);else if(entry.kind.equals("image"))WidgetImages.remove(activity,entry.image);rebuild();
            }).create()));
            panel.addView(remove);
        }
        updateValues();return panel;
    }
    private FrameLayout.LayoutParams editorBounds(){
        int available=canvas.getWidth()>0?canvas.getWidth():activity.getResources().getDisplayMetrics().widthPixels;
        int gravity=(editorLeft?Gravity.LEFT:Gravity.RIGHT)|Gravity.BOTTOM;
        // Prefer the opposite vertical half of the selected item; folds remain available in either position.
        if(selected!=null&&selected.frame!=null&&selected.frame.getTop()+selected.frame.getHeight()/2>canvas.getHeight()/2)gravity=(editorLeft?Gravity.LEFT:Gravity.RIGHT)|Gravity.TOP;
        // Keep the inspector itself out of the controls, even when selection is near the top.
        if(!reservedControls.isEmpty()&&(editorLeft?reservedControls.left<dp(200):reservedControls.right>available-dp(200)))gravity=(editorLeft?Gravity.LEFT:Gravity.RIGHT)|Gravity.TOP;
        return new FrameLayout.LayoutParams(Math.min(available,dp(200)),-2,gravity);
    }
    private Button editorButton(String text,int description,Runnable action){Button b=Ui.toolbarButton(activity,text,action);b.setContentDescription(activity.getString(description));b.setPadding(0,0,0,0);b.setMinWidth(0);b.setMinimumWidth(0);return b;}
    private int geometry(Entry e,int axis){return axis==0?(hubFromRight?screenX(e):e.x):axis==1?e.y:axis==2?e.w:e.h;}
    private boolean resizable(Entry e,int axis){return axis<2||e.info==null||e.mode!=WidgetGeometry.NORMAL||(e.info.resizeMode&(axis==2?AppWidgetProviderInfo.RESIZE_HORIZONTAL:AppWidgetProviderInfo.RESIZE_VERTICAL))!=0;}
    private void updateValues(){updateOverlapHint();if(selected==null)return;String[] names={"X","Y",activity.getString(R.string.widget_edit_width),activity.getString(R.string.widget_edit_height)};for(int i=0;i<4;i++)if(values[i]!=null)values[i].setText(names[i]+"\n"+geometry(selected,i)+" dp");}
    private void adjust(Entry e,int axis,int value){
        if(!resizable(e,axis))return;int aw=Math.max(1,pxToDp(canvas.getWidth())),ah=Math.max(1,pxToDp(canvas.getHeight()));
        int oldX=e.x,oldY=e.y,oldW=e.w,oldH=e.h;long oldOverlap=controlsOverlap(e);
        int left=sideX(e.x,e.w,aw);e.y=Math.max(0,Math.min(e.y,ah-Math.min(e.h,ah)));
        if(axis==0)left=Math.max(0,Math.min(value,aw-Math.min(e.w,aw)));
        if(axis==1)e.y=Math.max(0,Math.min(value,ah-Math.min(e.h,ah)));
        if(axis==2)e.w=WidgetGeometry.resize(value,aw-Math.min(left,aw-1),e.info==null?80:minimum(e,true),e.info==null?0:maximum(e,true));
        if(axis==3)e.h=WidgetGeometry.resize(value,ah-Math.min(e.y,ah-1),e.info==null?60:minimum(e,false),e.info==null?0:maximum(e,false));
        e.x=sideX(left,e.w,aw);
        if(controlsOverlap(e)>oldOverlap){e.x=oldX;e.y=oldY;e.w=oldW;e.h=oldH;}
        layout(e);save();updateValues();
    }
    private void number(Entry e,int axis,int name){
        EditText input=new EditText(activity);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setSingleLine(true);input.setText(String.valueOf(geometry(e,axis)));input.selectAll();
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(activity.getString(name)+" (dp)").setView(input).setNegativeButton(R.string.ui_cancel,null).setPositiveButton(R.string.ui_apply,null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{int n=Integer.parseInt(input.getText().toString());if(n<0||n>10000)throw new NumberFormatException();adjust(e,axis,n);dialog.dismiss();}catch(NumberFormatException ex){input.setError(activity.getString(R.string.widget_edit_number));}}));geometryDialog=dialog;DesktopBackdrop.showDialog(activity,dialog);
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
        }));geometryDialog=dialog;DesktopBackdrop.showDialog(activity,dialog);
    }
    private void relayout(){if(switchProfile()){rebuild();return;}for(Entry e:entries)layout(e);if(editor!=null&&!editorCollapsed)editor.setLayoutParams(editorBounds());}
    private void layout(Entry e){
        int availableW=pxToDp(canvas.getWidth()),availableH=pxToDp(canvas.getHeight());if(availableW<=0||availableH<=0)return;
        int w=Math.min(e.w,availableW),h=Math.min(e.h,availableH);
        // Only placement follows the Hub edge; provider pixels, IDs and stored X stay canonical.
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(w),dp(h));p.leftMargin=dp(sideX(e.x,w,availableW));p.topMargin=dp(Math.max(0,Math.min(e.y,availableH-h)));e.frame.setLayoutParams(p);
        View content=e.frame.getChildAt(0);content.setLayoutParams(new FrameLayout.LayoutParams(-1,-1));
        if(e.view!=null){
            int logicalW=w,logicalH=h;
            if(e.mode==WidgetGeometry.SCALE){logicalW=e.baseW>0?e.baseW:defaultSize(e.info,true);logicalH=e.baseH>0?e.baseH:defaultSize(e.info,false);}
            e.viewport.contentSize(dp(logicalW),dp(logicalH));
            if(android.os.Build.VERSION.SDK_INT>=31)e.view.updateAppWidgetSize(new Bundle(),Collections.singletonList(new android.util.SizeF(logicalW,logicalH)));
            else e.view.updateAppWidgetSize(null,logicalW,logicalH,logicalW,logicalH);
        }
    }
}
