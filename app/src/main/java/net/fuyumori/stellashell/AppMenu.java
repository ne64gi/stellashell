package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Display-scoped, focusable app menu. Never creates a window on the phone. */
final class AppMenu implements android.content.SharedPreferences.OnSharedPreferenceChangeListener {
    private final Context context;
    private final WindowManager windows;
    private final int displayId;
    private final List<Launches.App> all=new ArrayList<>(),shown=new ArrayList<>();
    private LinearLayout root;
    private TextView status;
    private EditText search;
    private BaseAdapter adapter;
    private Spinner groups;private ArrayAdapter<String> groupAdapter;private final List<String> groupNames=new ArrayList<>();
    private int groupMode;private String selectedGroup="";private boolean updatingGroups;


    AppMenu(Context context,WindowManager windows,int displayId) {
        this.context=context;this.windows=windows;this.displayId=displayId;
    }
    boolean isOpen(){return root!=null;}
    void close(){
        AppOrganization.prefs(context).unregisterOnSharedPreferenceChangeListener(this);
        if(root!=null)try{windows.removeViewImmediate(root);}catch(RuntimeException ignored){}
        root=null;
    }
    void open(ExecutorService loader){
        if(isOpen()){close();return;}
        Displays.require(context,displayId);adapter=null;
        AppOrganization.prefs(context).registerOnSharedPreferenceChangeListener(this);
        root=Ui.column(context);root.setBackground(Ui.rounded(context,Ui.PANEL,18));
        root.setPadding(Ui.dp(context,16),Ui.dp(context,14),Ui.dp(context,16),Ui.dp(context,12));
        LinearLayout heading=new LinearLayout(context);heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(Ui.text(context,context.getString(R.string.ui_apps),21,Ui.TEXT),new LinearLayout.LayoutParams(0,-2,1));
        Button settings=Ui.button(context,"⚙",()->{});settings.setContentDescription(context.getString(R.string.launcher_tools));settings.setOnClickListener(v->tools(settings));heading.addView(settings,new LinearLayout.LayoutParams(Ui.dp(context,52),Ui.dp(context,42)));
        heading.addView(Ui.button(context,context.getString(R.string.ui_close),this::close),new LinearLayout.LayoutParams(-2,Ui.dp(context,42)));
        root.addView(heading);
        search=new EditText(context);search.setSingleLine();search.setTextColor(Ui.TEXT);search.setHintTextColor(Ui.MUTED);
        search.setHint(context.getString(R.string.ui_search_by_name));search.setContentDescription(context.getString(R.string.ui_search_apps));search.setTextSize(16);
        root.addView(search,new LinearLayout.LayoutParams(-1,Ui.dp(context,48)));
        groups=new Spinner(context);groupAdapter=new ArrayAdapter<>(context,android.R.layout.simple_spinner_item,new ArrayList<>());groupAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);groups.setAdapter(groupAdapter);groups.setContentDescription(context.getString(R.string.launcher_filter));root.addView(groups,new LinearLayout.LayoutParams(-1,Ui.dp(context,44)));
        groups.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){if(updatingGroups)return;groupMode=Math.min(position,3);selectedGroup=position>=3&&position-3<groupNames.size()?groupNames.get(position-3):"";filter();}
        });refreshGroups();
        status=Ui.text(context,context.getString(R.string.ui_loading),12,Ui.MUTED);root.addView(status);
        ListView list=new ListView(context);list.setDividerHeight(0);
        adapter=new BaseAdapter(){
            public int getCount(){return shown.size();}
            public Object getItem(int position){return shown.get(position);}
            public long getItemId(int position){return position;}
            public View getView(int position,View recycled,android.view.ViewGroup parent){
                Launches.App app=shown.get(position);
                LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(context,6),Ui.dp(context,8),Ui.dp(context,8),Ui.dp(context,8));
                ImageView icon=new ImageView(context);icon.setImageDrawable(app.icon);
                row.addView(icon,new LinearLayout.LayoutParams(Ui.dp(context,32),Ui.dp(context,32)));
                TextView label=Ui.text(context,(Launches.pins(context).contains(app.component)?"★  ":"")+app.label,15,Ui.TEXT);
                label.setPadding(Ui.dp(context,12),0,0,0);label.setMaxLines(2);
                row.addView(label,new LinearLayout.LayoutParams(0,-2,1));row.setContentDescription(app.label);row.setOnClickListener(v->{if(groupMode==2)AppContextMenu.show(context,v,app.component,displayId,AppMenu.this::close,null,null);else{close();Launches.app(context,app.component,displayId);}});row.setOnLongClickListener(v->{AppContextMenu.show(context,v,app.component,displayId,AppMenu.this::close,null,null);return true;});row.setOnContextClickListener(v->{AppContextMenu.show(context,v,app.component,displayId,AppMenu.this::close,null,null);return true;});
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent,view,position,id)->{
            String component=shown.get(position).component;if(groupMode==2)AppContextMenu.show(context,view,component,displayId,this::close,null,null);else{close();Launches.app(context,component,displayId);}
        });
        list.setOnItemLongClickListener((parent,view,position,id)->{
            String component=shown.get(position).component;
            AppContextMenu.show(context,view,component,displayId,this::close,null,null);return true;
        });
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(Ui.text(context,context.getString(R.string.ui_right_click_or_long_press_for_launch_settings),12,Ui.MUTED));
        dismissOnOutside(root);
        root.setFocusableInTouchMode(true);
        View.OnKeyListener dismiss=(v,key,event)->{
            if((key==KeyEvent.KEYCODE_ESCAPE||key==KeyEvent.KEYCODE_BACK)&&event.getAction()==KeyEvent.ACTION_UP){close();return true;}
            return false;
        };
        root.setOnKeyListener(dismiss);search.setOnKeyListener(dismiss);list.setOnKeyListener(dismiss);
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){filter();}
            public void afterTextChanged(Editable value){}
        });
        int width=Math.min(Ui.dp(context,400),context.getResources().getDisplayMetrics().widthPixels-Ui.dp(context,16));
        int height=Math.min(Ui.dp(context,530),context.getResources().getDisplayMetrics().heightPixels-Ui.dp(context,130));
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(width,Math.max(Ui.dp(context,180),height),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.BOTTOM|Gravity.LEFT;params.x=Ui.dp(context,8);params.y=Ui.dp(context,68);
        params.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        params.setTitle("StellaShell app menu");
        try{windows.addView(root,params);root.requestFocus();}
        catch(RuntimeException failure){root=null;AppOrganization.prefs(context).unregisterOnSharedPreferenceChangeListener(this);throw failure;}
        View generation=root;
        loader.execute(()->{
            try{
                List<Launches.App> apps=Launches.catalog(context);
                new Handler(Looper.getMainLooper()).post(()->{
                    if(root!=generation)return;all.clear();all.addAll(apps);filter();
                });
            }catch(RuntimeException failure){new Handler(Looper.getMainLooper()).post(()->{
                if(root==generation)status.setText(context.getString(R.string.ui_could_not_load_apps));
            });}
        });
    }
    @Override public void onSharedPreferenceChanged(android.content.SharedPreferences prefs,String key){if(root!=null){if("groups".equals(key))refreshGroups();filter();}}
    private void refreshGroups(){
        updatingGroups=true;groupNames.clear();groupNames.addAll(AppOrganization.groups(context));
        groupAdapter.clear();groupAdapter.add(context.getString(R.string.launcher_all));groupAdapter.add(context.getString(R.string.launcher_ungrouped));groupAdapter.add(context.getString(R.string.launcher_hidden));groupAdapter.addAll(groupNames);
        int position=groupMode<3?groupMode:groupNames.indexOf(selectedGroup)+3;
        if(groupMode==3&&!groupNames.contains(selectedGroup)){groupMode=0;selectedGroup="";position=0;}
        groups.setSelection(position);updatingGroups=false;
    }
    private void tools(View anchor){
        PopupMenu popup=new PopupMenu(context,anchor);Menu menu=popup.getMenu();
        int[] labels={R.string.ui_wallpaper,R.string.ui_add_widget,R.string.widget_edit_toggle,R.string.ui_new_shortcut,R.string.ui_snap_icons_to_grid,R.string.ui_display_settings,R.string.ui_desktop_settings};
        int[] actions={3,6,7,2,1,4,5};
        SubMenu desktop=menu.addSubMenu(context.getString(R.string.launcher_desktop_tools));
        for(int i=0;i<labels.length;i++){int action=actions[i];desktop.add(context.getString(labels[i])).setOnMenuItemClickListener(item->{close();Launches.desktopAction(context,displayId,action);return true;});}
        menu.add(context.getString(R.string.launcher_new_group)).setOnMenuItemClickListener(item->{groupDialog(null);return true;});
        if(groupMode==3){
            String group=selectedGroup;
            menu.add(context.getString(R.string.launcher_rename_group)).setOnMenuItemClickListener(item->{groupDialog(group);return true;});
            menu.add(context.getString(R.string.launcher_delete_group)).setOnMenuItemClickListener(item->{
                android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(context).setTitle(R.string.launcher_delete_group).setMessage(R.string.launcher_delete_group_note).setNegativeButton(R.string.ui_cancel,null).setPositiveButton(R.string.ui_remove,(d,w)->AppOrganization.renameGroup(context,group,"")).create();showDialog(dialog);return true;
            });
        }
        menu.add(context.getString(R.string.launcher_hidden)).setOnMenuItemClickListener(item->{groups.setSelection(2);return true;});
        menu.add(context.getString(R.string.launcher_hide_homes)).setOnMenuItemClickListener(item->{
            Set<String> packages=new HashSet<>();
            for(android.content.pm.ResolveInfo info:context.getPackageManager().queryIntentActivities(new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),0))if(info.activityInfo!=null)packages.add(info.activityInfo.packageName);
            for(Launches.App app:all)if(packages.contains(android.content.ComponentName.unflattenFromString(app.component).getPackageName()))AppOrganization.hide(context,app.component,true);
            groups.setSelection(2);return true;
        });
        popup.show();
    }
    private void showDialog(android.app.AlertDialog dialog){dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);dialog.show();}
    private void groupDialog(String old){
        EditText input=new EditText(context);input.setSingleLine(true);input.setHint(R.string.launcher_group_name);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});if(old!=null)input.setText(old);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(context).setTitle(old==null?R.string.launcher_new_group:R.string.launcher_rename_group).setView(input).setNegativeButton(R.string.ui_cancel,null).setPositiveButton(R.string.ui_save,null).create();
        showDialog(dialog);dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String name=input.getText().toString().trim();
            if(name.isEmpty()||AppOrganization.groups(context).contains(name)&&!name.equals(old)){input.setError(context.getString(R.string.launcher_group_invalid));return;}
            if(old==null)AppOrganization.addGroup(context,name);else AppOrganization.renameGroup(context,old,name);
            selectedGroup=name;groupMode=3;refreshGroups();filter();dialog.dismiss();
        });
    }
    // ACTION_OUTSIDE is not a click on this view; all visible controls retain
    // their normal accessibility click actions.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void dismissOnOutside(View view){
        view.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_OUTSIDE){close();return true;}return false;});
    }
    private void filter(){
        if(root==null||adapter==null)return;
        String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);shown.clear();
        for(Launches.App app:all){
            boolean hidden=AppOrganization.hidden(context,app.component);String group=AppOrganization.group(context,app.component);
            if(groupMode==2?!hidden:hidden)continue;
            if(groupMode==1&&!group.isEmpty()||groupMode==3&&!selectedGroup.equals(group))continue;
            if(app.label.toLowerCase(Locale.ROOT).contains(query)||app.component.toLowerCase(Locale.ROOT).contains(query))shown.add(app);
        }
        status.setText(shown.isEmpty()?context.getString(R.string.ui_no_matching_apps):context.getResources().getQuantityString(R.plurals.app_count,shown.size(),shown.size()));adapter.notifyDataSetChanged();
    }
}
