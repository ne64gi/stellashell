package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Pack selection and per-component editing, also usable without desktop extensions. */
public final class IconSettingsActivity extends Activity {
    private final ExecutorService loader=Executors.newSingleThreadExecutor();
    private String component="";private LinearLayout root;private int generation;private boolean importing;
    static void open(Context c,int display,String component){
        try{c.startActivity(new Intent(c,IconSettingsActivity.class).putExtra("component",component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());}
        catch(RuntimeException e){Ui.message(c,e.getMessage());}
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);component=IconTheme.canonical(getIntent().getStringExtra("component"));editor();}
    private void page(String title){
        generation++;root=Ui.column(this);root.setPadding(dp(20),dp(16),dp(20),dp(16));root.setBackgroundColor(Ui.BG);setContentView(root);
        root.addView(Ui.text(this,title,23,Ui.TEXT));
    }
    private void action(int text,Runnable run){root.addView(Ui.button(this,getString(text),run),new LinearLayout.LayoutParams(-1,-2));}
    private void editor(){
        page(getString(component.isEmpty()?R.string.icons_title:R.string.icons_edit));
        if(component.isEmpty()){
            String selected=Launches.prefs(this).getString(IconTheme.PACK,"");String label=getString(R.string.icons_original);
            if(!selected.isEmpty())try{label=getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(selected,0)).toString();}catch(Exception e){label=getString(R.string.icons_pack_missing);}
            Ui.note(root,getString(R.string.icons_current,label));Ui.note(root,getString(R.string.icons_note));
            action(R.string.icons_choose_pack,()->packs(false));
            action(R.string.icons_original,()->{IconTheme.selectPack(this,"");editor();});
            action(R.string.icons_individual,this::apps);
        }else{
            try{
                android.content.pm.ActivityInfo info=getPackageManager().getActivityInfo(ComponentName.unflattenFromString(component),0);
                Ui.note(root,info.loadLabel(getPackageManager()).toString());ImageView preview=new ImageView(this);
                preview.setImageDrawable(IconTheme.resolve(this,component,info.loadIcon(getPackageManager())));root.addView(preview,new LinearLayout.LayoutParams(dp(72),dp(72)));
            }catch(Exception ignored){}
            Ui.note(root,getString(R.string.icons_override_note));
            action(R.string.icons_choose_from_pack,()->packs(true));
            action(R.string.icons_image,()->{if(importing)return;try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),41);}catch(RuntimeException e){Ui.message(this,getString(R.string.icons_image_failed));}});
            action(R.string.icons_original,()->{IconTheme.override(this,component,"default");editor();});
            action(R.string.icons_follow_pack,()->{IconTheme.override(this,component,"");editor();});
        }
        action(R.string.ui_close,this::finish);
        // Keep every control reachable in a small freeform window or landscape.
        ((ViewGroup)root.getParent()).removeView(root);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(root);setContentView(scroll);
    }
    private void packs(boolean individual){
        page(getString(R.string.icons_choose_pack));int current=generation;
        TextView loading=Ui.text(this,getString(R.string.ui_loading),14,Ui.MUTED);root.addView(loading);
        loader.execute(()->{
            List<IconTheme.Choice> choices=IconTheme.installed(this);
            runOnUiThread(()->{if(!active(current))return;root.removeView(loading);
                if(choices.isEmpty())Ui.note(root,getString(R.string.icons_no_packs));
                ListView list=new ListView(this);String[] labels=new String[choices.size()];for(int i=0;i<labels.length;i++)labels[i]=choices.get(i).label;
                list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,labels));root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
                list.setOnItemClickListener((a,v,n,id)->{String pkg=choices.get(n).pkg;if(individual)icons(pkg);else loadPack(pkg,pack->{if(pack==null||pack.mappings.isEmpty()){Ui.message(this,getString(R.string.icons_pack_empty));return;}IconTheme.selectPack(this,pkg);editor();});});
                action(R.string.ui_cancel,this::editor);
            });
        });
    }
    private void loadPack(String pkg,java.util.function.Consumer<IconTheme.Pack> done){
        int current=generation;loader.execute(()->{IconTheme.Pack pack=IconTheme.pack(this,pkg);runOnUiThread(()->{if(active(current))done.accept(pack);});});
    }
    private EditText search(){EditText input=new EditText(this);input.setSingleLine();input.setTextColor(Ui.TEXT);input.setHintTextColor(Ui.MUTED);input.setHint(R.string.ui_search_by_name);input.setContentDescription(getString(R.string.ui_search_by_name));root.addView(input);return input;}
    private void filter(EditText search,Runnable render){search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){render.run();}public void afterTextChanged(Editable e){}});}
    private void icons(String pkg){
        page(getString(R.string.icons_choose_from_pack));Ui.note(root,getString(R.string.ui_loading));
        loadPack(pkg,pack->{
            page(getString(R.string.icons_choose_from_pack));
            if(pack==null||pack.icons.isEmpty()){Ui.note(root,getString(R.string.icons_pack_empty));action(R.string.ui_cancel,this::editor);return;}
            EditText input=search();GridView grid=new GridView(this);grid.setColumnWidth(dp(88));grid.setNumColumns(GridView.AUTO_FIT);grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            List<String> names=new ArrayList<>(pack.icons);Collections.sort(names);List<String> shown=new ArrayList<>(names);
            BaseAdapter adapter=new BaseAdapter(){
                public int getCount(){return shown.size();}public Object getItem(int n){return shown.get(n);}public long getItemId(int n){return n;}
                public View getView(int n,View recycled,android.view.ViewGroup parent){
                    LinearLayout cell;if(recycled instanceof LinearLayout)cell=(LinearLayout)recycled;else{
                        cell=Ui.column(IconSettingsActivity.this);cell.setGravity(Gravity.CENTER);cell.setPadding(dp(4),dp(8),dp(4),dp(8));
                        ImageView image=new ImageView(IconSettingsActivity.this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);cell.addView(image,new LinearLayout.LayoutParams(dp(44),dp(44)));
                        TextView label=Ui.text(IconSettingsActivity.this,"",11,Ui.TEXT);label.setGravity(Gravity.CENTER);label.setMaxLines(2);label.setEllipsize(TextUtils.TruncateAt.END);cell.addView(label,new LinearLayout.LayoutParams(-1,dp(34)));
                    }
                    String name=shown.get(n);Drawable icon=pack.drawable(name);((ImageView)cell.getChildAt(0)).setImageDrawable(icon);((TextView)cell.getChildAt(1)).setText(name);cell.setContentDescription(name);return cell;
                }
            };
            grid.setAdapter(adapter);root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
            filter(input,()->{shown.clear();String query=input.getText().toString().trim().toLowerCase(Locale.ROOT);for(String name:names)if(name.toLowerCase(Locale.ROOT).contains(query))shown.add(name);adapter.notifyDataSetChanged();});
            grid.setOnItemClickListener((a,v,n,id)->{String name=shown.get(n);if(pack.drawable(name)==null){Ui.message(this,getString(R.string.icons_pack_empty));return;}IconTheme.override(this,component,"pack:"+pkg+":"+name);editor();});
            action(R.string.ui_cancel,this::editor);
        });
    }
    private void apps(){
        page(getString(R.string.icons_individual));int current=generation;Ui.note(root,getString(R.string.ui_loading));
        loader.execute(()->{List<Launches.App> all=Launches.catalog(this);runOnUiThread(()->{
            if(!active(current))return;page(getString(R.string.icons_individual));EditText input=search();ListView list=new ListView(this);List<Launches.App> shown=new ArrayList<>(all);
            ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,new ArrayList<>());list.setAdapter(adapter);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
            Runnable render=()->{shown.clear();adapter.clear();String query=input.getText().toString().trim().toLowerCase(Locale.ROOT);for(Launches.App app:all)if(app.label.toLowerCase(Locale.ROOT).contains(query)){shown.add(app);adapter.add(app.label);}adapter.notifyDataSetChanged();};render.run();filter(input,render);
            list.setOnItemClickListener((a,v,n,id)->IconSettingsActivity.open(this,getDisplay().getDisplayId(),shown.get(n).component));action(R.string.ui_cancel,this::editor);
        });});
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=41||result!=RESULT_OK||data==null||data.getData()==null||component.isEmpty())return;
        importing=true;Ui.message(this,getString(R.string.ui_loading));String target=component;
        loader.execute(()->{
            File file=null;try{file=IconTheme.importImage(this,data.getData());}catch(Exception ignored){}File imported=file;
            runOnUiThread(()->{importing=false;if(isFinishing()||isDestroyed()){if(imported!=null)imported.delete();return;}
                if(imported==null){Ui.message(this,getString(R.string.icons_image_failed));return;}IconTheme.override(this,target,"file:"+imported.getName());editor();
            });
        });
    }
    private boolean active(int current){return !isFinishing()&&!isDestroyed()&&generation==current;}
    @Override protected void onDestroy(){generation++;loader.shutdownNow();super.onDestroy();}
    private int dp(int value){return Ui.dp(this,value);}
}
