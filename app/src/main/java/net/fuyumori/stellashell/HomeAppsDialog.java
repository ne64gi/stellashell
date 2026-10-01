package net.fuyumori.stellashell;

import android.app.Dialog;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** An Activity-owned drawer: no overlay permission, service, or Shizuku needed. */
final class HomeAppsDialog extends Dialog {
    private final HomeActivity home;
    private final ExecutorService loader=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<Launches.App> catalog=new ArrayList<>();
    private LinearLayout rows;private EditText search;private TextView empty;
    private boolean loaded;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener iconChanges=(p,k)->{if(IconTheme.changed(k)&&isShowing())render();};
    HomeAppsDialog(HomeActivity home){super(home,Appearance.theme());this.home=home;}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);Launches.prefs(home).registerOnSharedPreferenceChangeListener(iconChanges);
        LinearLayout root=Ui.column(home);root.setPadding(Ui.dp(home,18),Ui.dp(home,18),Ui.dp(home,18),Ui.dp(home,12));
        root.setBackground(Appearance.surface(home,20));
        search=new EditText(home);search.setSingleLine();search.setTextColor(Ui.TEXT);search.setHintTextColor(Ui.MUTED);
        search.setHint(R.string.ui_search_by_name);search.setContentDescription(home.getString(R.string.ui_search_apps));root.addView(search,new LinearLayout.LayoutParams(-1,Ui.dp(home,52)));
        ScrollView scroll=new ScrollView(home);rows=Ui.column(home);scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        empty=Ui.text(home,home.getString(R.string.home_loading),14,Ui.MUTED);root.addView(empty);
        root.addView(Ui.text(home,home.getString(R.string.ui_right_click_or_long_press_for_launch_settings),12,Ui.MUTED));
        setContentView(root);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){render();}public void afterTextChanged(Editable s){}});
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        loader.execute(()->{
            try{
                List<Launches.App> found=Launches.catalog(home);
                main.post(()->{if(!isShowing())return;catalog.addAll(found);loaded=true;render();});
            }catch(RuntimeException error){main.post(()->{if(isShowing()){loaded=true;empty.setText(R.string.ui_could_not_load_apps);}});}
        });
    }
    @Override public void show(){
        super.show();android.graphics.Rect available=home.getWindowManager().getCurrentWindowMetrics().getBounds();
        getWindow().setLayout(Math.min(Ui.dp(home,640),Math.max(1,available.width()-Ui.dp(home,24))),Math.max(1,(int)(available.height()*.8)));
    }
    private void render(){
        rows.removeAllViews();String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);int count=0;
        int width=home.getWindowManager().getCurrentWindowMetrics().getBounds().width();int columns=Math.max(2,Math.min(5,width/Ui.dp(home,100)));
        LinearLayout row=null;
        for(Launches.App app:catalog){
            if(AppOrganization.hidden(home,app.component)||!app.label.toLowerCase(Locale.ROOT).contains(query))continue;
            if(count%columns==0){row=new LinearLayout(home);rows.addView(row);}
            LinearLayout cell=Ui.column(home);cell.setGravity(Gravity.CENTER);cell.setPadding(Ui.dp(home,4),Ui.dp(home,8),Ui.dp(home,4),Ui.dp(home,8));
            cell.setBackground(Ui.toolbarBackground(home,12));cell.setFocusable(true);cell.setContentDescription(app.label);
            ImageView icon=new ImageView(home);icon.setImageDrawable(AppIcons.forApp(home,app.component,app.icon));cell.addView(icon,new LinearLayout.LayoutParams(Ui.dp(home,40),Ui.dp(home,40)));
            TextView name=Ui.text(home,app.label,12,Ui.TEXT);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setGravity(Gravity.CENTER);cell.addView(name,new LinearLayout.LayoutParams(-1,-2));
            cell.setOnClickListener(v->{dismiss();Launches.app(home,app.component,0);});
            cell.setOnLongClickListener(v->{AppContextMenu.show(home,cell,app.component,0,this::dismiss,null,null);return true;});
            cell.setOnContextClickListener(View::performLongClick);
            row.addView(cell,new LinearLayout.LayoutParams(0,Ui.dp(home,100),1));count++;
        }
        if(row!=null)for(int i=count%columns;i>0&&i<columns;i++)row.addView(new View(home),new LinearLayout.LayoutParams(0,1,1));
        empty.setVisibility(count==0?View.VISIBLE:View.GONE);empty.setText(loaded?R.string.home_no_apps:R.string.home_loading);
    }
    @Override public void dismiss(){Launches.prefs(home).unregisterOnSharedPreferenceChangeListener(iconChanges);loader.shutdownNow();main.removeCallbacksAndMessages(null);super.dismiss();}
}
