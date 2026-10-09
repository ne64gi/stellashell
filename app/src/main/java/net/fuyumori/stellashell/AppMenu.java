package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.layout.DockPlacement;

import net.fuyumori.stellashell.feature.search.WebSearchSettings;
import net.fuyumori.stellashell.feature.search.SearchSettings;
import net.fuyumori.stellashell.feature.search.StartSearchSession;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.text.Collator;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** One display-scoped Start surface, with an in-surface modal folder panel. */
final class AppMenu implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final Context context;
    private final WindowManager windows;
    private final int displayId;
    private final boolean activityHosted;
    private final SearchSettings webSettings;
    private StartSearchSession webSearchSession;
    private WorkArea area(){return activityHosted?WorkArea.read(context,windows.getCurrentWindowMetrics().getWindowInsets()):WorkArea.get(context,displayId);}
    private final List<Launches.App> all=new ArrayList<>();
    private final Set<android.app.AlertDialog> dialogs=new HashSet<>();
    private FrameLayout root,folderLayer;
    private LinearLayout main,content;
    private ScrollView scroll;
    private EditText search;
    private Button webSearchAction;
    private String openGroup;
    private View folderAnchor;
    private boolean hiddenMode,loaded,renderQueued,folderOnly;
    private ExecutorService catalogLoader;
    private long catalogRequest;
    private int menuWidth,menuHeight;
    private PopupMenu activePopup;private long outsideDown=-1;
    void toggle(ExecutorService loader,long downTime){if(downTime>0&&downTime==outsideDown)return;open(loader);}

    AppMenu(Context context,WindowManager windows,int displayId){this.context=context;this.windows=windows;this.displayId=displayId;this.activityHosted=context instanceof HomeActivity&&displayId==0;webSettings=WebSearchSettings.of(context);}
    boolean isOpen(){return root!=null;}
    void relayout(){
        if(root==null)return;WorkArea area=area();
        menuWidth=Math.max(1,Math.min(dp(640),area.application.width()-dp(24)));
        menuHeight=Math.max(1,Math.min(dp(720),area.application.height()-dp(24)));
        WindowManager.LayoutParams p=(WindowManager.LayoutParams)root.getLayoutParams();p.width=menuWidth;p.height=menuHeight;
        position(area,p);windows.updateViewLayout(root,p);
        ShellPanels.bounds(displayId,this,new android.graphics.Rect(p.x,p.y,p.x+p.width,p.y+p.height));
    }
    private void position(WorkArea area,WindowManager.LayoutParams p){
        int[] point=DockPlacement.menu(area.application.left,area.application.top,area.application.right,area.application.bottom,
            menuWidth,menuHeight,dp(12),ShellSettings.of(context).snapshot().externalDock.edge.storedValue(),displayId==0?null:ShellRuntime.navigationBounds(displayId));
        p.x=point[0];p.y=point[1];
    }
    void back(){if(openGroup!=null)closeFolder();else close();}
    void close(){
        ShellPanels.release(displayId,this);
        AppOrganization.prefs(context).unregisterOnSharedPreferenceChangeListener(this);
        Launches.prefs(context).unregisterOnSharedPreferenceChangeListener(this);
        if(webSearchSession!=null)webSearchSession.close();
        webSearchSession=null;
        if(activePopup!=null)activePopup.dismiss();
        activePopup=null;
        for(android.app.AlertDialog dialog:new HashSet<>(dialogs))dialog.dismiss();
        dialogs.clear();
        if(root!=null)try{windows.removeViewImmediate(root);}catch(RuntimeException ignored){}
        // The menu owner outlives its window. Do not keep the detached view tree
        // (and every catalog icon) reachable while Start is closed.
        root=null;
        main=null;
        content=null;
        scroll=null;
        search=null;
        webSearchAction=null;
        folderLayer=null;
        folderAnchor=null;
        openGroup=null;
        all.clear();
        catalogLoader=null;
        catalogRequest++;
        loaded=false;
        folderOnly=false;
        renderQueued=false;
    }
    void openGroup(ExecutorService loader,String group){
        if(isOpen())close();open(loader);folderOnly=true;main.setVisibility(View.GONE);root.setBackgroundColor(android.graphics.Color.TRANSPARENT);openGroup=group;buildFolder();
    }
    void open(ExecutorService loader){
        if(isOpen()){close();return;}
        if(displayId!=0)Displays.require(context,displayId);StartPins.initialize(context,displayId);
        AppOrganization.initialize(context);
        AppOrganization.prefs(context).registerOnSharedPreferenceChangeListener(this);
        Launches.prefs(context).registerOnSharedPreferenceChangeListener(this);
        hiddenMode=false;loaded=false;all.clear();
        catalogLoader=loader;
        List<Launches.App> cached=Launches.cachedCatalog(context);
        if(cached!=null){all.addAll(cached);loaded=true;}
        WorkArea area=area();
        menuWidth=Math.max(1,Math.min(dp(640),area.application.width()-dp(24)));
        menuHeight=Math.max(1,Math.min(dp(720),area.application.height()-dp(24)));
        root=new FrameLayout(context);root.setBackground(Appearance.surface(context,22));root.setClipToOutline(true);
        main=Ui.column(context);main.setPadding(dp(20),dp(18),dp(20),dp(12));root.addView(main,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout heading=new LinearLayout(context);heading.setGravity(Gravity.CENTER_VERTICAL);
        search=new EditText(context);search.setTypeface(Appearance.face);search.setSingleLine();search.setTextSize(16);search.setTextColor(Ui.TEXT);search.setHintTextColor(Ui.MUTED);
        search.setHint(R.string.ui_search_by_name);search.setContentDescription(context.getString(R.string.ui_search_apps));
        search.setPadding(dp(14),0,dp(14),0);search.setBackground(Ui.rounded(context,Ui.BG,12));
        heading.addView(search,new LinearLayout.LayoutParams(0,dp(48),1));
        Button settings=smallButton("⚙",context.getString(R.string.launcher_tools));settings.setTextSize(28);settings.setBackground(null);settings.setStateListAnimator(null);settings.setElevation(0);settings.setOnClickListener(v->tools(settings));heading.addView(settings,new LinearLayout.LayoutParams(dp(48),dp(48)));
        main.addView(heading);
        webSearchAction=Ui.button(context,"",()->{});webSearchAction.setTextSize(14);webSearchAction.setMaxLines(1);webSearchAction.setEllipsize(TextUtils.TruncateAt.END);
        webSearchAction.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);webSearchAction.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search,0,0,0);webSearchAction.setCompoundDrawablePadding(dp(8));
        webSearchAction.setVisibility(View.GONE);main.addView(webSearchAction,new LinearLayout.LayoutParams(-1,dp(48)));
        FrameLayout openedRoot=root;Button openedWebAction=webSearchAction;
        StartSearchSession openedWebSession=new StartSearchSession(webSettings,(engine,query)->{
            if(root!=openedRoot||webSearchAction!=openedWebAction)return false;
            return WebSearchLauncher.open(context,displayId,engine,query);
        },this::queueRender);
        webSearchSession=openedWebSession;
        webSearchAction.setOnClickListener(v->{
            if(root!=openedRoot||webSearchAction!=openedWebAction)return;
            if(openedWebSession.launch(search.getText().toString()))close();
        });
        if(TaskState.of(context).compact(context,displayId))
            main.addView(Ui.text(context,context.getString(R.string.workspace_roles_hint),12,Ui.MUTED));
        scroll=new ScrollView(context);scroll.setFillViewport(false);scroll.setClipToPadding(false);scroll.setPadding(0,dp(8),0,0);
        content=Ui.column(context);scroll.addView(content);main.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=new LinearLayout(context);footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView hint=Ui.text(context,context.getString(R.string.ui_right_click_or_long_press_for_launch_settings),12,Ui.MUTED);hint.setPadding(0,dp(8),0,0);footer.addView(hint,new LinearLayout.LayoutParams(0,-2,1));
        if(displayId!=0){
            ImageButton power=new ImageButton(context);power.setImageResource(R.drawable.ic_session_power);power.setColorFilter(Ui.TEXT);
            power.setBackground(null);power.setStateListAnimator(null);power.setElevation(0);power.setPadding(dp(15),dp(15),dp(15),dp(15));
            power.setContentDescription(context.getString(R.string.exit_desktop));power.setTooltipText(context.getString(R.string.exit_desktop));
            power.setOnClickListener(v->showDialog(new android.app.AlertDialog.Builder(context).setTitle(R.string.exit_desktop)
                    .setMessage(R.string.exit_desktop_session_note).setNegativeButton(R.string.ui_cancel,null)
                    .setPositiveButton(R.string.exit_desktop,(dialog,which)->{close();ShellRuntime.stop(context);}).create()));
            footer.addView(power,new LinearLayout.LayoutParams(dp(48),dp(48)));
        }
        main.addView(footer);
        View.OnKeyListener back=(v,key,event)->{
            if((key==KeyEvent.KEYCODE_ESCAPE||key==KeyEvent.KEYCODE_BACK)&&event.getAction()==KeyEvent.ACTION_UP){back();return true;}return false;
        };
        root.setFocusableInTouchMode(true);root.setOnKeyListener(back);search.setOnKeyListener(back);webSearchAction.setOnKeyListener(back);dismissOnOutside(root);
        EditText openedSearch=search;
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                if(root==null||search!=openedSearch)return;
                closeFolder();render();if(scroll!=null)scroll.scrollTo(0,0);
            }
            public void afterTextChanged(Editable text){}
        });
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(menuWidth,menuHeight,activityHosted?WindowManager.LayoutParams.TYPE_APPLICATION_PANEL:WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;params.setFitInsetsTypes(0);position(area,params);
        params.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        params.setTitle("StellaShell app menu");
        ShellPanels.activate(displayId,this,this::close);
        try{windows.addView(root,params);ShellPanels.track(displayId,this,root);root.requestFocus();render();}catch(RuntimeException error){close();throw error;}
        if(!loaded)loadCatalog();
    }
    private void loadCatalog(){
        if(root==null||catalogLoader==null)return;
        long request=++catalogRequest;
        java.lang.ref.WeakReference<AppMenu> owner=new java.lang.ref.WeakReference<>(this);
        Context source=context.getApplicationContext();
        catalogLoader.execute(()->{
            try{
                List<Launches.App> apps=Launches.catalog(source);
                new Handler(Looper.getMainLooper()).post(()->{
                    AppMenu menu=owner.get();
                    if(menu!=null&&menu.root!=null&&menu.catalogRequest==request){menu.all.clear();menu.all.addAll(apps);menu.loaded=true;menu.render();}
                });
            }catch(RuntimeException error){new Handler(Looper.getMainLooper()).post(()->{
                AppMenu menu=owner.get();
                if(menu!=null&&menu.root!=null&&menu.catalogRequest==request&&!menu.loaded){menu.content.removeAllViews();menu.note(menu.content,R.string.ui_could_not_load_apps);}
            });}
        });
    }
    private int dp(int value){return Ui.dp(context,value);}
    private Button smallButton(String text,String description){Button b=Ui.button(context,text,()->{});b.setPadding(0,0,0,0);b.setMinWidth(0);b.setMinimumWidth(0);b.setContentDescription(description);b.setTooltipText(description);return b;}
    private void note(LinearLayout parent,int text){TextView view=Ui.text(context,context.getString(text),14,Ui.MUTED);view.setPadding(dp(6),dp(12),dp(6),dp(16));parent.addView(view);}
    private void heading(LinearLayout parent,String text){TextView view=Ui.text(context,text,16,Ui.TEXT);view.setTypeface(null,Typeface.BOLD);view.setPadding(dp(6),dp(18),0,dp(12));parent.addView(view);}
    private static String normalized(String text){return Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    private boolean matches(String label,String component,String query){String value=normalized(label+" "+component);for(String term:normalized(query).trim().split("\\s+"))if(!value.contains(term))return false;return true;}
    private List<Launches.App> members(String group){List<Launches.App> apps=new ArrayList<>();for(Launches.App app:all)if(!AppOrganization.hidden(context,displayId,app.component)&&group.equals(AppOrganization.group(context,app.component)))apps.add(app);return apps;}
    private void render(){
        if(root==null)return;
        updateWebSearch();
        int y=scroll.getScrollY();content.removeAllViews();
        if(!loaded){note(content,R.string.ui_loading);return;}
        String query=search.getText().toString().trim();
        int columns=Math.max(2,Math.min(6,(menuWidth-dp(40))/dp(96)));
        if(!hiddenMode&&query.isEmpty()){
            heading(content,context.getString(R.string.start_pinned_heading));
            List<View> pins=new ArrayList<>();
            for(String component:StartPins.get(context,displayId)){
                String group=GroupEntries.name(context,component);
                if(group!=null){pins.add(groupTile(group));continue;}
                for(Launches.App app:all)if(component.equals(app.component)&&!AppOrganization.hidden(context,component)){pins.add(appTile(app));break;}
            }
            if(pins.isEmpty())note(content,R.string.start_pins_empty);else content.addView(grid(pins,columns));
        }
        heading(content,context.getString(hiddenMode?R.string.launcher_hidden:query.isEmpty()?R.string.launcher_all:R.string.start_search_results));
        List<Launches.App> apps=new ArrayList<>();List<String> groups=new ArrayList<>();
        if(!hiddenMode)for(String group:AppOrganization.groups(context))if(query.isEmpty()||matches(group,"",query))groups.add(group);
        for(Launches.App app:all){
            if(AppOrganization.hidden(context,displayId,app.component)!=hiddenMode)continue;
            if(!matches(app.label,app.component,query))continue;
            String group=AppOrganization.group(context,app.component);
            if(!hiddenMode&&query.isEmpty()&&!group.isEmpty()&&groups.contains(group))continue;
            apps.add(app);
        }
        // Mix folders and ungrouped applications alphabetically; searches reach inside folders.
        List<Object> items=new ArrayList<>();items.addAll(apps);items.addAll(groups);Collator sort=Collator.getInstance();
        items.sort((a,b)->sort.compare(a instanceof String?(String)a:((Launches.App)a).label,b instanceof String?(String)b:((Launches.App)b).label));
        List<View> tiles=new ArrayList<>();for(Object item:items)tiles.add(item instanceof String?groupTile((String)item):appTile((Launches.App)item));
        if(tiles.isEmpty())note(content,R.string.ui_no_matching_apps);else content.addView(grid(tiles,columns));
        FrameLayout renderedRoot=root;ScrollView renderedScroll=scroll;
        renderedScroll.post(()->{if(root==renderedRoot)renderedScroll.scrollTo(0,y);});
        if(openGroup!=null){if(!AppOrganization.groups(context).contains(openGroup))closeFolder();else buildFolder();}
    }
    private void updateWebSearch(){
        StartSearchSession.State state=webSearchSession.state(search.getText().toString());
        search.setHint(state.enabled?net.fuyumori.stellashell.feature.search.R.string.web_search_hint:R.string.ui_search_by_name);
        search.setContentDescription(context.getString(state.enabled?net.fuyumori.stellashell.feature.search.R.string.web_search_hint:R.string.ui_search_apps));
        webSearchAction.setVisibility(state.visible?View.VISIBLE:View.GONE);
        if(state.visible){String label=context.getString(net.fuyumori.stellashell.feature.search.R.string.web_search_action,state.engineName,state.query);webSearchAction.setText(label);webSearchAction.setContentDescription(label);}
        else{webSearchAction.setText("");webSearchAction.setContentDescription(null);}
    }
    private View grid(List<View> tiles,int columns){
        LinearLayout rows=Ui.column(context);
        for(int start=0;start<tiles.size();start+=columns){
            LinearLayout row=new LinearLayout(context);
            for(int col=0;col<columns;col++){View tile=start+col<tiles.size()?tiles.get(start+col):new View(context);row.addView(tile,new LinearLayout.LayoutParams(0,dp(102),1));}
            rows.addView(row);
        }
        return rows;
    }
    private LinearLayout tile(String label){
        LinearLayout tile=Ui.column(context);tile.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);tile.setPadding(dp(6),dp(8),dp(6),dp(4));
        tile.setBackground(new RippleDrawable(ColorStateList.valueOf(0x446ee7c8),Ui.rounded(context,0x00000000,12),Ui.rounded(context,0xffffffff,12)));
        tile.setFocusable(true);tile.setTooltipText(label);tile.setContentDescription(label);
        tile.setOnHoverListener((v,event)->{v.setAlpha(event.getAction()==MotionEvent.ACTION_HOVER_EXIT?1f:.78f);return false;});
        tile.setOnKeyListener((v,key,event)->{if((key==KeyEvent.KEYCODE_ESCAPE||key==KeyEvent.KEYCODE_BACK)&&event.getAction()==KeyEvent.ACTION_UP){back();return true;}return false;});
        return tile;
    }
    private void label(LinearLayout tile,String name){TextView label=Ui.text(context,name,12,Ui.TEXT);label.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);label.setMaxLines(2);label.setEllipsize(TextUtils.TruncateAt.END);label.setPadding(0,dp(6),0,0);label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);tile.addView(label,new LinearLayout.LayoutParams(-1,-2));}
    private View appTile(Launches.App app){
        LinearLayout tile=tile(app.label);ImageView icon=new MenuAppIcon(context,app.component,app.icon);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);tile.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));label(tile,app.label);
        Runnable options=()->AppContextMenu.show(context,tile,app.component,displayId,this::close,null,null);
        tile.setOnClickListener(v->{if(hiddenMode)options.run();else{close();Launches.app(context,app.component,displayId);}});
        tile.setOnLongClickListener(v->{options.run();return true;});tile.setOnContextClickListener(v->{options.run();return true;});return tile;
    }
    private View groupTile(String group){
        LinearLayout tile=tile(group);FrameLayout preview=new FrameLayout(context);preview.setBackgroundResource(R.drawable.ic_start_folder);
        List<Launches.App> apps=members(group);
        for(int i=0;i<Math.min(4,apps.size());i++){ImageView icon=new MenuAppIcon(context,apps.get(i).component,apps.get(i).icon);FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(13),dp(13));p.leftMargin=dp(7+(i%2)*15);p.topMargin=dp(12+(i/2)*14);preview.addView(icon,p);}
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);tile.addView(preview,new LinearLayout.LayoutParams(dp(44),dp(44)));label(tile,group);
        tile.setContentDescription(context.getString(R.string.start_group_accessibility,group,apps.size()));
        tile.setOnClickListener(v->{folderAnchor=tile;openGroup=group;buildFolder();});
        tile.setOnLongClickListener(v->{groupTools(tile,group);return true;});tile.setOnContextClickListener(v->{groupTools(tile,group);return true;});return tile;
    }
    private void closeFolder(){
        if(folderOnly){close();return;}
        // Search changes also call this. With no folder open, leave the editor's
        // focus and composing span alone instead of focusing the menu root.
        if(folderLayer==null&&openGroup==null)return;
        if(root!=null&&folderLayer!=null)root.removeView(folderLayer);folderLayer=null;openGroup=null;
        if(main!=null)main.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if(folderAnchor!=null&&folderAnchor.isAttachedToWindow())folderAnchor.requestFocus();else if(root!=null)root.requestFocus();folderAnchor=null;
    }
    private void buildFolder(){
        if(root==null||openGroup==null)return;
        if(folderLayer!=null)root.removeView(folderLayer);
        main.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        folderLayer=new FrameLayout(context);folderLayer.setBackgroundColor(0x8808121c);folderLayer.setOnClickListener(v->closeFolder());root.addView(folderLayer,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout panel=Ui.column(context);panel.setPadding(dp(18),dp(16),dp(18),dp(18));panel.setBackground(Appearance.surface(context,18));panel.setElevation(dp(16));panel.setOnClickListener(v->{});
        int width=Math.min(dp(480),menuWidth-dp(40));int height=Math.min(dp(450),Math.max(dp(160),(root.getHeight()>0?root.getHeight():menuHeight)-dp(64)));
        FrameLayout.LayoutParams box=new FrameLayout.LayoutParams(width,height,Gravity.CENTER);folderLayer.addView(panel,box);
        String group=openGroup;LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=Ui.text(context,group,20,Ui.TEXT);title.setTypeface(null,Typeface.BOLD);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button membership=smallButton("⚙",context.getString(R.string.apps_group_manage));membership.setOnClickListener(v->selectApps(group));header.addView(membership,new LinearLayout.LayoutParams(dp(44),dp(44)));
        Button tools=smallButton("⋯",context.getString(R.string.start_group_options));tools.setOnClickListener(v->groupTools(tools,group));header.addView(tools,new LinearLayout.LayoutParams(dp(44),dp(44)));
        Button close=smallButton("×",context.getString(R.string.ui_close));close.setOnClickListener(v->closeFolder());header.addView(close,new LinearLayout.LayoutParams(dp(44),dp(44)));panel.addView(header);
        ScrollView scroller=new ScrollView(context);LinearLayout children=Ui.column(context);List<View> tiles=new ArrayList<>();for(Launches.App app:members(group))tiles.add(appTile(app));
        if(tiles.isEmpty())note(children,R.string.start_group_empty);else children.addView(grid(tiles,Math.max(2,Math.min(4,(width-dp(36))/dp(96)))));
        scroller.addView(children);panel.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
        panel.setFocusableInTouchMode(true);panel.requestFocus();panel.setOnKeyListener((v,key,event)->{if((key==KeyEvent.KEYCODE_ESCAPE||key==KeyEvent.KEYCODE_BACK)&&event.getAction()==KeyEvent.ACTION_UP){closeFolder();return true;}return false;});
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key){
        if(root==null || prefs==Launches.prefs(context)&&!WorkspaceProfile.changed(key,"start_pinned")&&!IconTheme.changed(key))return;
        if(prefs==Launches.prefs(context)&&IconTheme.REVISION.equals(key))loadCatalog();
        queueRender();
    }
    private void queueRender(){
        if(root==null)return;
        if(renderQueued)return;renderQueued=true;FrameLayout generation=root;
        root.post(()->{if(root==generation){renderQueued=false;render();}});
    }
    private void selectApps(String group){
        if(!loaded){Ui.message(context,context.getString(R.string.ui_loading));return;}
        android.app.AlertDialog dialog=AppChecklist.create(context,displayId,all,group);showDialog(dialog);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    private void groupTools(View anchor,String group){
        PopupMenu popup=new PopupMenu(context,anchor);activePopup=popup;
        String reference=GroupEntries.reference(context,group);
        popup.getMenu().add(Launches.desktop(context).contains(reference)?R.string.ui_remove_from_desktop:R.string.ui_add_to_desktop).setOnMenuItemClickListener(item->{Launches.toggleDesktop(context,reference);return true;});
        popup.getMenu().add(StartPins.get(context,displayId).contains(reference)?R.string.start_unpin:R.string.start_pin).setOnMenuItemClickListener(item->{StartPins.toggle(context,displayId,reference);return true;});
        popup.getMenu().add(R.string.apps_group_manage).setOnMenuItemClickListener(item->{selectApps(group);return true;});
        popup.getMenu().add(R.string.launcher_rename_group).setOnMenuItemClickListener(item->{groupDialog(group);return true;});
        popup.getMenu().add(R.string.launcher_delete_group).setOnMenuItemClickListener(item->{
            android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(context).setTitle(R.string.launcher_delete_group).setMessage(R.string.launcher_delete_group_note).setNegativeButton(R.string.ui_cancel,null).setPositiveButton(R.string.ui_remove,(d,w)->AppOrganization.renameGroup(context,group,"")).create();showDialog(dialog);return true;
        });popup.show();
    }
    private void tools(View anchor){
        PopupMenu popup=new PopupMenu(context,anchor);activePopup=popup;Menu menu=popup.getMenu();
        SubMenu desktop=menu.addSubMenu(R.string.menu_desktop);
        SubMenu widgets=desktop.addSubMenu(R.string.menu_widgets);
        desktopAction(widgets,R.string.ui_add_widget,6);desktopAction(widgets,R.string.widget_edit_toggle,7);
        desktopAction(desktop,R.string.ui_wallpaper,3);
        SubMenu customize=menu.addSubMenu(R.string.menu_customize);
        customize.add(R.string.appearance_title).setOnMenuItemClickListener(item->{close();AppearanceActivity.open(context,displayId);return true;});
        SubMenu apps=customize.addSubMenu(R.string.apps_visible_title);
        apps.add(R.string.apps_visible_title).setOnMenuItemClickListener(item->{selectApps(null);return true;});
        apps.add(hiddenMode?R.string.launcher_all:R.string.launcher_hidden).setOnMenuItemClickListener(item->{hiddenMode=!hiddenMode;closeFolder();render();return true;});
        SubMenu groups=customize.addSubMenu(R.string.menu_groups);
        groups.add(R.string.launcher_new_group).setOnMenuItemClickListener(item->{groupDialog(null);return true;});
        for(String group:AppOrganization.groups(context))groups.add(group).setOnMenuItemClickListener(item->{groupTools(anchor,group);return true;});
        desktopAction(customize,R.string.ui_new_shortcut,2);
        desktopAction(customize,R.string.ui_snap_icons_to_grid,1);
        SubMenu settings=menu.addSubMenu(R.string.menu_settings);
        settings.add(net.fuyumori.stellashell.feature.search.R.string.web_search_settings_title).setOnMenuItemClickListener(item->{close();SearchSettingsActivity.open(context,displayId);return true;});
        settings.add(R.string.menu_android_settings).setOnMenuItemClickListener(item->{
            close();
            try{
                context.startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        android.app.ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());
                if(displayId!=Display.DEFAULT_DISPLAY)Toast.makeText(context,R.string.opening_on_phone,Toast.LENGTH_SHORT).show();
            }catch(RuntimeException e){Launches.problem(context,e.getMessage());}
            return true;
        });
        settings.add(R.string.menu_stella_settings).setOnMenuItemClickListener(item->{close();Launches.settings(context,displayId);return true;});
        apps.add(R.string.launcher_hide_homes).setOnMenuItemClickListener(item->{
            Set<String> packages=new HashSet<>();for(android.content.pm.ResolveInfo info:context.getPackageManager().queryIntentActivities(new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),0))if(info.activityInfo!=null)packages.add(info.activityInfo.packageName);
            for(Launches.App app:all)if(packages.contains(android.content.ComponentName.unflattenFromString(app.component).getPackageName()))AppOrganization.hide(context,displayId,app.component,true);
            hiddenMode=true;closeFolder();render();return true;
        });
        popup.show();
    }
    private void desktopAction(Menu target,int label,int action){target.add(label).setOnMenuItemClickListener(item->{close();Launches.desktopAction(context,displayId,action);return true;});}
    private void showDialog(android.app.AlertDialog dialog){if(!activityHosted)dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);dialogs.add(dialog);dialog.setOnDismissListener(d->dialogs.remove(dialog));dialog.show();}
    private void groupDialog(String old){
        EditText input=new EditText(context);input.setSingleLine();input.setHint(R.string.launcher_group_name);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});if(old!=null)input.setText(old);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(context).setTitle(old==null?R.string.launcher_new_group:R.string.launcher_rename_group).setView(input).setNegativeButton(R.string.ui_cancel,null).setPositiveButton(R.string.ui_save,null).create();showDialog(dialog);
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String name=input.getText().toString().trim();if(name.isEmpty()||AppOrganization.groups(context).contains(name)&&!name.equals(old)){input.setError(context.getString(R.string.launcher_group_invalid));return;}
            if(old==null)AppOrganization.addGroup(context,name);else AppOrganization.renameGroup(context,old,name);
            openGroup=name;hiddenMode=false;render();dialog.dismiss();if(old==null)selectApps(name);
        });
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void dismissOnOutside(View view){view.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_OUTSIDE){outsideDown=event.getDownTime();close();return true;}return false;});}
}
