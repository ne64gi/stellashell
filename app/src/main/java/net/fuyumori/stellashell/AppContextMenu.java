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
        if(move!=null)menu.add(c.getString(R.string.ui_move_2)).setOnMenuItemClickListener(m->{move.run();return true;});
        menu.add(c.getString(R.string.ui_open)).setOnMenuItemClickListener(m->{dismiss.run();if(task!=null)session.action(task,"focus");else Launches.app(c,component,display);return true;});
        menu.add(c.getString(R.string.ui_open_in_new_window)).setOnMenuItemClickListener(m->{dismiss.run();Launches.app(c,component,display,true);return true;});
        SubMenu mode=menu.addSubMenu(c.getString(R.string.ui_launch_mode_next_launch));String[] modes={c.getString(R.string.ui_windowed),c.getString(R.string.ui_maximized),c.getString(R.string.ui_fullscreen),c.getString(R.string.ui_last_state)};
        for(AppLaunchProfile.Mode value:AppLaunchProfile.Mode.values())mode.add(1,value.ordinal(),value.ordinal(),modes[value.ordinal()]).setCheckable(true).setChecked(p.launchMode==value).setOnMenuItemClickListener(m->{p.launchMode=value;Profiles.save(c,component,p);return true;});mode.setGroupCheckable(1,true,true);
        SubMenu size=menu.addSubMenu(c.getString(R.string.ui_initial_window_size));String[] sizes={"800 × 600","1280 × 720",c.getString(R.string.ui_50_of_screen_width_and_height),c.getString(R.string.ui_last_size),c.getString(R.string.ui_custom)};
        for(AppLaunchProfile.Size value:AppLaunchProfile.Size.values())size.add(2,value.ordinal(),value.ordinal(),sizes[value.ordinal()]).setCheckable(true).setChecked(p.size==value).setOnMenuItemClickListener(m->{if(value==AppLaunchProfile.Size.CUSTOM)customSize(c,component,p);else{p.size=value;Profiles.save(c,component,p);}return true;});size.setGroupCheckable(2,true,true);
        SubMenu position=menu.addSubMenu(c.getString(R.string.ui_initial_position));String[] positions={c.getString(R.string.ui_automatic),c.getString(R.string.ui_center),c.getString(R.string.ui_last_position)};
        for(AppLaunchProfile.Position value:AppLaunchProfile.Position.values())position.add(3,value.ordinal(),value.ordinal(),positions[value.ordinal()]).setCheckable(true).setChecked(p.position==value).setOnMenuItemClickListener(m->{p.position=value;Profiles.save(c,component,p);return true;});position.setGroupCheckable(3,true,true);
        menu.add(c.getString(R.string.ui_remember_position_and_size)).setCheckable(true).setChecked(p.rememberBounds).setOnMenuItemClickListener(m->{p.rememberBounds=!p.rememberBounds;Profiles.save(c,component,p);return true;});
        if(task!=null){SubMenu window=menu.addSubMenu(c.getString(R.string.ui_current_window));String[] labels={c.getString(R.string.ui_minimize),c.getString(R.string.ui_maximized),c.getString(R.string.ui_restore_size),c.getString(R.string.ui_snap_left),c.getString(R.string.ui_snap_right),c.getString(R.string.ui_close)};String[] actions={"minimize","maximize","restore","left","right","close"};for(int i=0;i<labels.length;i++){String action=actions[i];window.add(labels[i]).setOnMenuItemClickListener(m->{session.action(task,action);return true;});}}
        menu.add(Launches.desktop(c).contains(component)?c.getString(R.string.ui_remove_from_desktop):c.getString(R.string.ui_add_to_desktop)).setOnMenuItemClickListener(m->{Launches.toggleDesktop(c,component);return true;});
        menu.add(StartPins.get(c).contains(component)?c.getString(R.string.start_unpin):c.getString(R.string.start_pin)).setOnMenuItemClickListener(m->{StartPins.toggle(c,component);return true;});
        menu.add(Launches.pins(c).contains(component)?c.getString(R.string.ui_unpin_from_taskbar):c.getString(R.string.ui_pin_to_taskbar)).setOnMenuItemClickListener(m->{Launches.togglePin(c,component);return true;});
        menu.add(c.getString(R.string.ui_app_info)).setOnMenuItemClickListener(m->{dismiss.run();try{c.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+p.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());}catch(RuntimeException e){Launches.problem(c,e.getMessage());}return true;});
        SubMenu groups=menu.addSubMenu(c.getString(R.string.launcher_assign_group));
        java.util.List<String> names=new java.util.ArrayList<>();names.add("");names.addAll(AppOrganization.groups(c));
        for(String group:names)groups.add(group.isEmpty()?c.getString(R.string.launcher_ungrouped):group).setCheckable(true).setChecked(group.equals(AppOrganization.group(c,component))).setOnMenuItemClickListener(item->{AppOrganization.assign(c,component,group);return true;});
        menu.add(c.getString(AppOrganization.hidden(c,component)?R.string.launcher_show_app:R.string.launcher_hide_app)).setOnMenuItemClickListener(item->{AppOrganization.hide(c,component,!AppOrganization.hidden(c,component));return true;});
        popup.show();
    }
    private static void customSize(Context c,String component,AppLaunchProfile p){
        LinearLayout form=Ui.column(c);form.setPadding(Ui.dp(c,20),0,Ui.dp(c,20),0);
        EditText width=new EditText(c),height=new EditText(c);width.setHint(c.getString(R.string.ui_width_px));height.setHint(c.getString(R.string.ui_height_px));width.setContentDescription(c.getString(R.string.ui_initial_width));height.setContentDescription(c.getString(R.string.ui_initial_height));width.setInputType(2);height.setInputType(2);width.setText(String.valueOf(p.width));height.setText(String.valueOf(p.height));form.addView(width);form.addView(height);
        AlertDialog dialog=new AlertDialog.Builder(c).setTitle(c.getString(R.string.ui_initial_size_display_pixels)).setView(form).setNegativeButton(c.getString(R.string.ui_cancel),null).setPositiveButton(c.getString(R.string.ui_save),null).create();
        if(!(c instanceof Activity))dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{int w=Integer.parseInt(width.getText().toString()),h=Integer.parseInt(height.getText().toString());if(w<240||w>16384||h<160||h>16384)throw new NumberFormatException();p.width=w;p.height=h;p.size=AppLaunchProfile.Size.CUSTOM;Profiles.save(c,component,p);dialog.dismiss();}catch(NumberFormatException e){width.setError(c.getString(R.string.ui_width_must_be_240_16384_and_height_160_16384));}}));dialog.show();
    }
}
