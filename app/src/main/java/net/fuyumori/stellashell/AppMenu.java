package net.fuyumori.stellashell;

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
    private final List<Launches.App> all=new ArrayList<>();
    private final Set<android.app.AlertDialog> dialogs=new HashSet<>();
    private FrameLayout root,folderLayer;
    private LinearLayout main,content;
    private ScrollView scroll;
    private EditText search;
    private String openGroup;
    private View folderAnchor;
    private boolean hiddenMode,loaded,renderQueued;
    private int menuWidth,menuHeight;
    private PopupMenu activePopup;

    AppMenu(Context context,WindowManager windows,int displayId){this.context=context;this.windows=windows;this.displayId=displayId;}
    boolean isOpen(){return root!=null;}
    void back(){if(openGroup!=null)closeFolder();else close();}
    void close(){
        AppOrganization.prefs(context).unregisterOnSharedPreferenceChangeListener(this);
        Launches.prefs(context).unregisterOnSharedPreferenceChangeListener(this);
        if(activePopup!=null)activePopup.dismiss();activePopup=null;
        for(android.app.AlertDialog dialog:new HashSet<>(dialogs))dialog.dismiss();dialogs.clear();
        if(root!=null)try{windows.removeViewImmediate(root);}catch(RuntimeException ignored){}
        root=null;folderLayer=null;openGroup=null;folderAnchor=null;renderQueued=false;
    }
    void open(ExecutorService loader){
        if(isOpen()){close();return;}
        Displays.require(context,displayId);StartPins.initialize(context);
        AppOrganization.prefs(context).registerOnSharedPreferenceChangeListener(this);
        Launches.prefs(context).registerOnSharedPreferenceChangeListener(this);
        hiddenMode=false;loaded=false;all.clear();
        menuWidth=Math.min(dp(640),context.getResources().getDisplayMetrics().widthPixels-dp(24));
        menuHeight=Math.min(dp(720),context.getResources().getDisplayMetrics().heightPixels-dp(140));
        root=new FrameLayout(context);root.setBackground(Ui.rounded(context,Ui.PANEL,22));root.setClipToOutline(true);
        main=Ui.column(context);main.setPadding(dp(20),dp(18),dp(20),dp(12));root.addView(main,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout heading=new LinearLayout(context);heading.setGravity(Gravity.CENTER_VERTICAL);
        search=new EditText(context);search.setSingleLine();search.setTextSize(16);search.setTextColor(Ui.TEXT);search.setHintTextColor(Ui.MUTED);
        search.setHint(R.string.ui_search_by_name);search.setContentDescription(context.getString(R.string.ui_search_apps));
        search.setPadding(dp(14),0,dp(14),0);search.setBackground(Ui.rounded(context,0xff121e2c,12));
        heading.addView(search,new LinearLayout.LayoutParams(0,dp(48),1));
        Button settings=smallButton("⚙",context.getString(R.string.launcher_tools));settings.setOnClickListener(v->tools(settings));heading.addView(settings,new LinearLayout.LayoutParams(dp(48),dp(48)));
        Button close=smallButton("×",context.getString(R.string.ui_close));close.setOnClickListener(v->close());heading.addView(close,new LinearLayout.LayoutParams(dp(44),dp(48)));main.addView(heading);
        scroll=new ScrollView(context);scroll.setFillViewport(false);scroll.setClipToPadding(false);scroll.setPadding(0,dp(8),0,0);
        content=Ui.column(context);scroll.addView(content);main.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView hint=Ui.text(context,context.getString(R.string.ui_right_click_or_long_press_for_launch_settings),12,Ui.MUTED);hint.setPadding(0,dp(8),0,0);main.addView(hint);
        View.OnKeyListener back=(v,key,event)->{
            if((key==KeyEvent.KEYCODE_ESCAPE||key==KeyEvent.KEYCODE_BACK)&&event.getAction()==KeyEvent.ACTION_UP){back();return true;}return false;
        };
        root.setFocusableInTouchMode(true);root.setOnKeyListener(back);search.setOnKeyListener(back);dismissOnOutside(root);
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){closeFolder();render();scroll.scrollTo(0,0);}
            public void afterTextChanged(Editable text){}
        });
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(menuWidth,menuHeight,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.BOTTOM|Gravity.LEFT;params.x=dp(12);params.y=dp(72);
        params.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        params.setTitle("StellaShell app menu");
        try{windows.addView(root,params);root.requestFocus();render();}catch(RuntimeException error){close();throw error;}
        FrameLayout generation=root;
        loader.execute(()->{
            try{
                List<Launches.App> apps=Launches.catalog(context);
                new Handler(Looper.getMainLooper()).post(()->{if(root==generation){all.clear();all.addAll(apps);loaded=true;render();}});
            }catch(RuntimeException error){new Handler(Looper.getMainLooper()).post(()->{
                if(root==generation){content.removeAllViews();note(content,R.string.ui_could_not_load_apps);}
            });}
        });
    }
    private int dp(int value){return Ui.dp(context,value);}
    private Button smallButton(String text,String description){Button b=Ui.button(context,text,()->{});b.setPadding(0,0,0,0);b.setMinWidth(0);b.setMinimumWidth(0);b.setContentDescription(description);b.setTooltipText(description);return b;}
    private void note(LinearLayout parent,int text){TextView view=Ui.text(context,context.getString(text),14,Ui.MUTED);view.setPadding(dp(6),dp(12),dp(6),dp(16));parent.addView(view);}
    private void heading(LinearLayout parent,String text){TextView view=Ui.text(context,text,16,Ui.TEXT);view.setTypeface(null,Typeface.BOLD);view.setPadding(dp(6),dp(18),0,dp(12));parent.addView(view);}
    private static String normalized(String text){return Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    private boolean matches(String label,String component,String query){String value=normalized(label+" "+component);for(String term:normalized(query).trim().split("\\s+"))if(!value.contains(term))return false;return true;}
    private List<Launches.App> members(String group){List<Launches.App> apps=new ArrayList<>();for(Launches.App app:all)if(!AppOrganization.hidden(context,app.component)&&group.equals(AppOrganization.group(context,app.component)))apps.add(app);return apps;}
    private void render(){
        if(root==null)return;
        int y=scroll.getScrollY();content.removeAllViews();
        if(!loaded){note(content,R.string.ui_loading);return;}
        String query=search.getText().toString().trim();
        int columns=Math.max(2,Math.min(6,(menuWidth-dp(40))/dp(96)));
        if(!hiddenMode&&query.isEmpty()){
            heading(content,context.getString(R.string.start_pinned_heading));
            List<View> pins=new ArrayList<>();
            for(String component:StartPins.get(context))for(Launches.App app:all)if(component.equals(app.component)&&!AppOrganization.hidden(context,component)){pins.add(appTile(app));break;}
            if(pins.isEmpty())note(content,R.string.start_pins_empty);else content.addView(grid(pins,columns));
        }
        heading(content,context.getString(hiddenMode?R.string.launcher_hidden:query.isEmpty()?R.string.launcher_all:R.string.start_search_results));
        List<Launches.App> apps=new ArrayList<>();List<String> groups=new ArrayList<>();
        if(!hiddenMode)for(String group:AppOrganization.groups(context))if(query.isEmpty()||matches(group,"",query))groups.add(group);
        for(Launches.App app:all){
            if(AppOrganization.hidden(context,app.component)!=hiddenMode)continue;
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
        scroll.post(()->{if(root!=null)scroll.scrollTo(0,y);});
        if(openGroup!=null){if(!AppOrganization.groups(context).contains(openGroup))closeFolder();else buildFolder();}
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
        LinearLayout tile=tile(app.label);ImageView icon=new ImageView(context);icon.setImageDrawable(app.icon);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);tile.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));label(tile,app.label);
        Runnable options=()->AppContextMenu.show(context,tile,app.component,displayId,this::close,null,null);
        tile.setOnClickListener(v->{if(hiddenMode)options.run();else{close();Launches.app(context,app.component,displayId);}});
        tile.setOnLongClickListener(v->{options.run();return true;});tile.setOnContextClickListener(v->{options.run();return true;});return tile;
    }
    private View groupTile(String group){
        LinearLayout tile=tile(group);FrameLayout preview=new FrameLayout(context);preview.setBackgroundResource(R.drawable.ic_start_folder);
        List<Launches.App> apps=members(group);
        for(int i=0;i<Math.min(4,apps.size());i++){ImageView icon=new ImageView(context);icon.setImageDrawable(apps.get(i).icon);FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(13),dp(13));p.leftMargin=dp(7+(i%2)*15);p.topMargin=dp(12+(i/2)*14);preview.addView(icon,p);}
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);tile.addView(preview,new LinearLayout.LayoutParams(dp(44),dp(44)));label(tile,group);
        tile.setContentDescription(context.getString(R.string.start_group_accessibility,group,apps.size()));
        tile.setOnClickListener(v->{folderAnchor=tile;openGroup=group;buildFolder();});
        tile.setOnLongClickListener(v->{groupTools(tile,group);return true;});tile.setOnContextClickListener(v->{groupTools(tile,group);return true;});return tile;
    }
    private void closeFolder(){
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
        LinearLayout panel=Ui.column(context);panel.setPadding(dp(18),dp(16),dp(18),dp(18));panel.setBackground(Ui.rounded(context,0xff263445,18));panel.setElevation(dp(16));panel.setOnClickListener(v->{});
        int width=Math.min(dp(480),menuWidth-dp(40));int height=Math.min(dp(450),Math.max(dp(160),(root.getHeight()>0?root.getHeight():menuHeight)-dp(64)));
        FrameLayout.LayoutParams box=new FrameLayout.LayoutParams(width,height,Gravity.CENTER);folderLayer.addView(panel,box);
        String group=openGroup;LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=Ui.text(context,group,20,Ui.TEXT);title.setTypeface(null,Typeface.BOLD);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button tools=smallButton("⋯",context.getString(R.string.start_group_options));tools.setOnClickListener(v->groupTools(tools,group));header.addView(tools,new LinearLayout.LayoutParams(dp(44),dp(44)));
        Button close=smallButton("×",context.getString(R.string.ui_close));close.setOnClickListener(v->closeFolder());header.addView(close,new LinearLayout.LayoutParams(dp(44),dp(44)));panel.addView(header);
        ScrollView scroller=new ScrollView(context);LinearLayout children=Ui.column(context);List<View> tiles=new ArrayList<>();for(Launches.App app:members(group))tiles.add(appTile(app));
        if(tiles.isEmpty())note(children,R.string.start_group_empty);else children.addView(grid(tiles,Math.max(2,Math.min(4,(width-dp(36))/dp(96)))));
        scroller.addView(children);panel.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
        panel.setFocusableInTouchMode(true);panel.requestFocus();panel.setOnKeyListener((v,key,event)->{if((key==KeyEvent.KEYCODE_ESCAPE||key==KeyEvent.KEYCODE_BACK)&&event.getAction()==KeyEvent.ACTION_UP){closeFolder();return true;}return false;});
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key){
        if(root==null || prefs==Launches.prefs(context)&&!"start_pinned".equals(key))return;
        if(renderQueued)return;renderQueued=true;FrameLayout generation=root;
        root.post(()->{renderQueued=false;if(root==generation)render();});
    }
    private void groupTools(View anchor,String group){
        PopupMenu popup=new PopupMenu(context,anchor);activePopup=popup;
        popup.getMenu().add(R.string.launcher_rename_group).setOnMenuItemClickListener(item->{groupDialog(group);return true;});
        popup.getMenu().add(R.string.launcher_delete_group).setOnMenuItemClickListener(item->{
            android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(context).setTitle(R.string.launcher_delete_group).setMessage(R.string.launcher_delete_group_note).setNegativeButton(R.string.ui_cancel,null).setPositiveButton(R.string.ui_remove,(d,w)->AppOrganization.renameGroup(context,group,"")).create();showDialog(dialog);return true;
        });popup.show();
    }
    private void tools(View anchor){
        PopupMenu popup=new PopupMenu(context,anchor);activePopup=popup;Menu menu=popup.getMenu();
        int[] labels={R.string.ui_wallpaper,R.string.ui_add_widget,R.string.widget_edit_toggle,R.string.ui_new_shortcut,R.string.ui_snap_icons_to_grid,R.string.ui_display_settings,R.string.ui_desktop_settings};int[] actions={3,6,7,2,1,4,5};
        SubMenu desktop=menu.addSubMenu(context.getString(R.string.launcher_desktop_tools));
        for(int i=0;i<labels.length;i++){int action=actions[i];desktop.add(context.getString(labels[i])).setOnMenuItemClickListener(item->{close();Launches.desktopAction(context,displayId,action);return true;});}
        menu.add(R.string.launcher_new_group).setOnMenuItemClickListener(item->{groupDialog(null);return true;});
        menu.add(hiddenMode?R.string.launcher_all:R.string.launcher_hidden).setOnMenuItemClickListener(item->{hiddenMode=!hiddenMode;closeFolder();render();return true;});
        menu.add(R.string.launcher_hide_homes).setOnMenuItemClickListener(item->{
            Set<String> packages=new HashSet<>();for(android.content.pm.ResolveInfo info:context.getPackageManager().queryIntentActivities(new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),0))if(info.activityInfo!=null)packages.add(info.activityInfo.packageName);
            for(Launches.App app:all)if(packages.contains(android.content.ComponentName.unflattenFromString(app.component).getPackageName()))AppOrganization.hide(context,app.component,true);
            hiddenMode=true;closeFolder();render();return true;
        });
        menu.add(R.string.reset_connection).setOnMenuItemClickListener(item->{close();DockService.resetConnection(context);return true;});
        menu.add(R.string.exit_desktop).setOnMenuItemClickListener(item->{close();DockService.stop(context);return true;});popup.show();
    }
    private void showDialog(android.app.AlertDialog dialog){dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);dialogs.add(dialog);dialog.setOnDismissListener(d->dialogs.remove(dialog));dialog.show();}
    private void groupDialog(String old){
        EditText input=new EditText(context);input.setSingleLine();input.setHint(R.string.launcher_group_name);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});if(old!=null)input.setText(old);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(context).setTitle(old==null?R.string.launcher_new_group:R.string.launcher_rename_group).setView(input).setNegativeButton(R.string.ui_cancel,null).setPositiveButton(R.string.ui_save,null).create();showDialog(dialog);
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String name=input.getText().toString().trim();if(name.isEmpty()||AppOrganization.groups(context).contains(name)&&!name.equals(old)){input.setError(context.getString(R.string.launcher_group_invalid));return;}
            if(old==null)AppOrganization.addGroup(context,name);else AppOrganization.renameGroup(context,old,name);
            openGroup=name;hiddenMode=false;render();dialog.dismiss();
        });
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void dismissOnOutside(View view){view.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_OUTSIDE){close();return true;}return false;});}
}
