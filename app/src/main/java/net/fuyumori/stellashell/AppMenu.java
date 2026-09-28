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
final class AppMenu {
    private final Context context;
    private final WindowManager windows;
    private final int displayId;
    private final List<Launches.App> all=new ArrayList<>(),shown=new ArrayList<>();
    private LinearLayout root;
    private TextView status;
    private EditText search;
    private BaseAdapter adapter;

    AppMenu(Context context,WindowManager windows,int displayId) {
        this.context=context;this.windows=windows;this.displayId=displayId;
    }
    boolean isOpen(){return root!=null;}
    void close(){
        if(root!=null)try{windows.removeViewImmediate(root);}catch(RuntimeException ignored){}
        root=null;
    }
    void open(ExecutorService loader){
        if(isOpen()){close();return;}
        Displays.require(context,displayId);
        root=Ui.column(context);root.setBackground(Ui.rounded(context,Ui.PANEL,18));
        root.setPadding(Ui.dp(context,16),Ui.dp(context,14),Ui.dp(context,16),Ui.dp(context,12));
        LinearLayout heading=new LinearLayout(context);heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(Ui.text(context,context.getString(R.string.ui_apps),21,Ui.TEXT),new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(Ui.button(context,context.getString(R.string.ui_close),this::close),new LinearLayout.LayoutParams(-2,Ui.dp(context,42)));
        root.addView(heading);
        search=new EditText(context);search.setSingleLine();search.setTextColor(Ui.TEXT);search.setHintTextColor(Ui.MUTED);
        search.setHint(context.getString(R.string.ui_search_by_name));search.setContentDescription(context.getString(R.string.ui_search_apps));search.setTextSize(16);
        root.addView(search,new LinearLayout.LayoutParams(-1,Ui.dp(context,48)));
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
                row.addView(label,new LinearLayout.LayoutParams(0,-2,1));row.setContentDescription(app.label);row.setOnClickListener(v->{close();Launches.app(context,app.component,displayId);});row.setOnLongClickListener(v->{AppContextMenu.show(context,v,app.component,displayId,AppMenu.this::close,null,null);return true;});row.setOnContextClickListener(v->{AppContextMenu.show(context,v,app.component,displayId,AppMenu.this::close,null,null);return true;});
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent,view,position,id)->{
            String component=shown.get(position).component;close();Launches.app(context,component,displayId);
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
        catch(RuntimeException failure){root=null;throw failure;}
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
    // ACTION_OUTSIDE is not a click on this view; all visible controls retain
    // their normal accessibility click actions.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void dismissOnOutside(View view){
        view.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_OUTSIDE){close();return true;}return false;});
    }
    private void filter(){
        if(root==null)return;
        String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);shown.clear();
        for(Launches.App app:all)if(app.label.toLowerCase(Locale.ROOT).contains(query)||app.component.toLowerCase(Locale.ROOT).contains(query))shown.add(app);
        status.setText(shown.isEmpty()?context.getString(R.string.ui_no_matching_apps):context.getResources().getQuantityString(R.plurals.app_count,shown.size(),shown.size()));adapter.notifyDataSetChanged();
    }
}
