package net.fuyumori.stellashell;

import android.app.AlertDialog;
import android.content.Context;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.text.Normalizer;
import java.util.*;

/** Shared, staged selector for launcher visibility and single-group membership. */
final class AppChecklist {
    static AlertDialog create(Context context,List<Launches.App> catalog,String group){
        return create(context,WorkspaceProfile.phone(context)?0:1,catalog,group);
    }
    static AlertDialog create(Context context,int displayId,List<Launches.App> catalog,String group){
        List<Launches.App> apps=new ArrayList<>(catalog),filtered=new ArrayList<>();
        Map<String,Boolean> initial=new HashMap<>();Set<String> selected=new HashSet<>();
        for(Launches.App app:apps){boolean checked=group==null?!AppOrganization.hidden(context,displayId,app.component):group.equals(AppOrganization.group(context,app.component));initial.put(app.component,checked);if(checked)selected.add(app.component);}
        LinearLayout panel=Ui.column(context);int pad=Ui.dp(context,16);panel.setPadding(pad,0,pad,0);
        if(group==null)Ui.note(panel,context.getString(displayId==0?R.string.apps_visible_phone_scope:R.string.apps_visible_desktop_scope));
        Ui.note(panel,context.getString(group==null?R.string.apps_visible_note:R.string.apps_group_note));
        EditText search=new EditText(context);search.setSingleLine();search.setHint(R.string.ui_search_by_name);search.setContentDescription(context.getString(R.string.apps_search));panel.addView(search,new LinearLayout.LayoutParams(-1,Ui.dp(context,48)));
        TextView count=Ui.text(context,"",13,Ui.MUTED);count.setPadding(0,Ui.dp(context,6),0,Ui.dp(context,6));
        Runnable updateCount=()->count.setText(context.getString(R.string.apps_selected_count,selected.size(),apps.size()));
        LinearLayout controls=new LinearLayout(context);panel.addView(controls);panel.addView(count);
        TextView empty=Ui.text(context,context.getString(R.string.ui_no_matching_apps),14,Ui.MUTED);panel.addView(empty);
        ListView list=new ListView(context);int height=Math.max(Ui.dp(context,120),Math.min(Ui.dp(context,440),context.getResources().getDisplayMetrics().heightPixels/2));panel.addView(list,new LinearLayout.LayoutParams(-1,height));
        BaseAdapter adapter=new BaseAdapter(){
            public int getCount(){return filtered.size();}
            public Object getItem(int position){return filtered.get(position);}
            public long getItemId(int position){return position;}
            public View getView(int position,View recycled,ViewGroup parent){
                LinearLayout row;CheckBox check;ImageView icon;TextView label,detail;
                if(recycled==null){
                    row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,Ui.dp(context,7),Ui.dp(context,8),Ui.dp(context,7));
                    check=new CheckBox(context);check.setFocusable(false);row.addView(check,new LinearLayout.LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
                    icon=new ImageView(context);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(icon,new LinearLayout.LayoutParams(Ui.dp(context,36),Ui.dp(context,36)));
                    LinearLayout texts=Ui.column(context);texts.setPadding(Ui.dp(context,12),0,0,0);label=Ui.text(context,"",16,Ui.TEXT);detail=Ui.text(context,"",11,Ui.MUTED);label.setMaxLines(2);detail.setMaxLines(2);detail.setEllipsize(TextUtils.TruncateAt.END);texts.addView(label);texts.addView(detail);row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
                    row.setTag(new Object[]{check,icon,label,detail});
                }else{row=(LinearLayout)recycled;Object[] views=(Object[])row.getTag();check=(CheckBox)views[0];icon=(ImageView)views[1];label=(TextView)views[2];detail=(TextView)views[3];}
                Launches.App app=filtered.get(position);check.setOnCheckedChangeListener(null);check.setChecked(selected.contains(app.component));check.setContentDescription(app.label);
                icon.setImageDrawable(AppIcons.forApp(context,app.component,app.icon));label.setText(app.label);
                String current=AppOrganization.group(context,app.component),subtitle=app.component;
                if(!current.isEmpty())subtitle+=" · "+current;
                if(AppOrganization.hidden(context,displayId,app.component))subtitle+=" · "+context.getString(R.string.apps_hidden_badge);
                detail.setText(subtitle);
                check.setOnCheckedChangeListener((button,checked)->{if(checked)selected.add(app.component);else selected.remove(app.component);updateCount.run();});
                return row;
            }
        };
        list.setAdapter(adapter);list.setOnItemClickListener((parent,view,position,id)->((CheckBox)((Object[])view.getTag())[0]).toggle());
        controls.addView(Ui.button(context,context.getString(R.string.apps_select_results),()->{for(Launches.App app:filtered)selected.add(app.component);adapter.notifyDataSetChanged();updateCount.run();}),new LinearLayout.LayoutParams(0,Ui.dp(context,48),1));
        controls.addView(Ui.button(context,context.getString(R.string.apps_clear_results),()->{for(Launches.App app:filtered)selected.remove(app.component);adapter.notifyDataSetChanged();updateCount.run();}),new LinearLayout.LayoutParams(0,Ui.dp(context,48),1));
        Runnable filter=()->{
            String query=normalize(search.getText().toString()).trim();filtered.clear();
            for(Launches.App app:apps){String value=normalize(app.label+" "+app.component);boolean matches=true;for(String term:query.split("\\s+"))if(!value.contains(term)){matches=false;break;}if(matches)filtered.add(app);}
            adapter.notifyDataSetChanged();empty.setVisibility(filtered.isEmpty()?View.VISIBLE:View.GONE);list.setSelection(0);
        };
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){filter.run();}public void afterTextChanged(Editable e){}});
        filter.run();updateCount.run();
        return new AlertDialog.Builder(context).setTitle(group==null?context.getString(R.string.apps_visible_title):context.getString(R.string.apps_group_title,group)).setView(panel)
            .setNegativeButton(R.string.ui_cancel,null).setPositiveButton(R.string.ui_save,(dialog,which)->{
                Map<String,Boolean> changes=new HashMap<>();for(Launches.App app:apps){boolean checked=selected.contains(app.component);if(checked!=initial.get(app.component))changes.put(app.component,checked);}
                if(!AppOrganization.applySelection(context,displayId,group,changes))Ui.message(context,context.getString(R.string.apps_group_missing));
            }).create();
    }
    private static String normalize(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
}
