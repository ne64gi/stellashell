package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;

/** The same launch profile on Start, desktop shortcuts and the taskbar. */
final class AppContextMenu {
    static void show(Context c,View anchor,String requested,int display,Runnable dismiss,TaskSession.Task task,TaskSession session){
        show(c,anchor,requested,display,dismiss,task,session,null);
    }
    static void show(Context c,View anchor,String requested,int display,Runnable dismiss,TaskSession.Task task,TaskSession session,Runnable move){
        String component=Profiles.requestedComponent(c,requested);AppLaunchProfile p=Profiles.get(c,component);PopupMenu popup=new PopupMenu(c,anchor);Menu menu=popup.getMenu();
        if(move!=null)menu.add("移動").setOnMenuItemClickListener(m->{move.run();return true;});
        menu.add("開く").setOnMenuItemClickListener(m->{dismiss.run();if(task!=null)session.action(task,"focus");else Launches.app(c,component,display);return true;});
        menu.add("新しいウィンドウで開く").setOnMenuItemClickListener(m->{dismiss.run();Launches.app(c,component,display,true);return true;});
        SubMenu mode=menu.addSubMenu("起動モード（次回起動時）");String[] modes={"ウィンドウ","最大化","フルスクリーン","前回の状態"};
        for(AppLaunchProfile.Mode value:AppLaunchProfile.Mode.values())mode.add(1,value.ordinal(),value.ordinal(),modes[value.ordinal()]).setCheckable(true).setChecked(p.launchMode==value).setOnMenuItemClickListener(m->{p.launchMode=value;Profiles.save(c,component,p);return true;});mode.setGroupCheckable(1,true,true);
        SubMenu size=menu.addSubMenu("初期ウィンドウサイズ");String[] sizes={"800 × 600","1280 × 720","幅・高さを画面の50%","前回のサイズ","カスタム…"};
        for(AppLaunchProfile.Size value:AppLaunchProfile.Size.values())size.add(2,value.ordinal(),value.ordinal(),sizes[value.ordinal()]).setCheckable(true).setChecked(p.size==value).setOnMenuItemClickListener(m->{if(value==AppLaunchProfile.Size.CUSTOM)customSize(c,component,p);else{p.size=value;Profiles.save(c,component,p);}return true;});size.setGroupCheckable(2,true,true);
        SubMenu position=menu.addSubMenu("起動位置");String[] positions={"自動","中央","前回の位置"};
        for(AppLaunchProfile.Position value:AppLaunchProfile.Position.values())position.add(3,value.ordinal(),value.ordinal(),positions[value.ordinal()]).setCheckable(true).setChecked(p.position==value).setOnMenuItemClickListener(m->{p.position=value;Profiles.save(c,component,p);return true;});position.setGroupCheckable(3,true,true);
        menu.add("位置・サイズを記憶").setCheckable(true).setChecked(p.rememberBounds).setOnMenuItemClickListener(m->{p.rememberBounds=!p.rememberBounds;Profiles.save(c,component,p);return true;});
        if(task!=null){SubMenu window=menu.addSubMenu("現在のウィンドウ");String[] labels={"最小化","最大化","元のサイズ","左半分","右半分","閉じる"};String[] actions={"minimize","maximize","restore","left","right","close"};for(int i=0;i<labels.length;i++){String action=actions[i];window.add(labels[i]).setOnMenuItemClickListener(m->{session.action(task,action);return true;});}}
        menu.add(Launches.desktop(c).contains(component)?"デスクトップから削除":"デスクトップに追加").setOnMenuItemClickListener(m->{Launches.toggleDesktop(c,component);return true;});
        menu.add(Launches.pins(c).contains(component)?"ピン留めを解除":"タスクバーにピン留め").setOnMenuItemClickListener(m->{Launches.togglePin(c,component);return true;});
        menu.add("アプリ情報").setOnMenuItemClickListener(m->{dismiss.run();try{c.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+p.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());}catch(RuntimeException e){Launches.problem(c,e.getMessage());}return true;});
        popup.show();
    }
    private static void customSize(Context c,String component,AppLaunchProfile p){
        LinearLayout form=Ui.column(c);form.setPadding(Ui.dp(c,20),0,Ui.dp(c,20),0);
        EditText width=new EditText(c),height=new EditText(c);width.setHint("幅（px）");height.setHint("高さ（px）");width.setContentDescription("起動幅");height.setContentDescription("起動高さ");width.setInputType(2);height.setInputType(2);width.setText(String.valueOf(p.width));height.setText(String.valueOf(p.height));form.addView(width);form.addView(height);
        AlertDialog dialog=new AlertDialog.Builder(c).setTitle("初期サイズ（表示座標 px）").setView(form).setNegativeButton("キャンセル",null).setPositiveButton("保存",null).create();
        if(!(c instanceof Activity))dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{int w=Integer.parseInt(width.getText().toString()),h=Integer.parseInt(height.getText().toString());if(w<240||w>16384||h<160||h>16384)throw new NumberFormatException();p.width=w;p.height=h;p.size=AppLaunchProfile.Size.CUSTOM;Profiles.save(c,component,p);dialog.dismiss();}catch(NumberFormatException e){width.setError("幅240以上・高さ160以上、16384以下で入力");}}));dialog.show();
    }
}
