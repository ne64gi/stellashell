package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.tasks.TaskModes;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import net.fuyumori.stellashell.core.launch.AppLaunchProfile;
import net.fuyumori.stellashell.core.launch.LaunchProfileSnapshot;

/** The same launch profile on Start, desktop shortcuts and the taskbar. */
final class AppContextMenu {
    static void show(Context c,View anchor,String requested,int display,Runnable dismiss,TaskSnapshot.Task task,TaskState taskState){
        show(c,anchor,requested,display,dismiss,task,taskState,null);
    }
    static void show(Context c,View anchor,String requested,int display,Runnable dismiss,TaskSnapshot.Task task,TaskState taskState,Runnable move){
        show(c,anchor,requested,display,dismiss,task,taskState,move,null);
    }
    /** Overlay callers may retain the popup's anchor until dismissal. */
    static PopupMenu show(Context c,View anchor,String requested,int display,Runnable dismiss,TaskSnapshot.Task task,TaskState taskState,Runnable move,Runnable onDismiss){
        TaskState state=taskState==null?TaskState.of(c):taskState;
        String component=Profiles.requestedComponent(c,requested);LaunchProfileSnapshot p=Profiles.snapshot(c,component);PopupMenu popup=new PopupMenu(c,anchor);Menu menu=popup.getMenu();
        menu.add(c.getString(R.string.ui_open)).setOnMenuItemClickListener(m->{dismiss.run();if(task!=null)Launches.focus(c,task,display,state);else Launches.app(c,component,display);return true;});
        ExternalAppLaunch.addToMenu(c,menu,component,dismiss);
        if(WorkspaceProfile.standard(c,display)&&Bridge.get(c).ready())menu.add(R.string.phone_floating).setOnMenuItemClickListener(m->{dismiss.run();Launches.app(c,component,display,false,true);return true;});
        if(!Launches.basicHome(c,display)){
        menu.add(c.getString(R.string.ui_open_in_new_window)).setOnMenuItemClickListener(m->{dismiss.run();Launches.app(c,component,display,true);return true;});
        SubMenu launch=menu.addSubMenu(1,0,10,c.getString(R.string.menu_launch));
        if(state.compact(c,display)){
            launch.add(R.string.workspace_primary).setCheckable(task!=null).setChecked(task!=null&&state.owns(task)&&state.primary()==task.id).setOnMenuItemClickListener(m->{dismiss.run();if(task!=null)Launches.role(c,task,display,true);else Launches.app(c,component,display,false,false);return true;});
            launch.add(R.string.workspace_floating).setCheckable(task!=null).setChecked(task!=null&&state.owns(task)&&state.primary()!=task.id).setOnMenuItemClickListener(m->{dismiss.run();if(task!=null)Launches.role(c,task,display,false);else Launches.app(c,component,display,false,true);return true;});
        }
        SubMenu mode=launch.addSubMenu(c.getString(R.string.ui_launch_mode_next_launch));String[] modes={c.getString(R.string.ui_windowed),c.getString(R.string.ui_maximized),c.getString(R.string.ui_fullscreen),c.getString(R.string.ui_last_state)};
        for(AppLaunchProfile.Mode value:AppLaunchProfile.Mode.values())mode.add(1,value.ordinal(),value.ordinal(),modes[value.ordinal()]).setCheckable(true).setChecked(p.launchMode==value).setOnMenuItemClickListener(m->{Profiles.setMode(c,component,value);return true;});mode.setGroupCheckable(1,true,true);
        SubMenu size=launch.addSubMenu(c.getString(R.string.ui_initial_window_size));String[] sizes={"800 × 600","1280 × 720",c.getString(R.string.ui_50_of_screen_width_and_height),c.getString(R.string.ui_last_size),c.getString(R.string.ui_custom)};
        for(AppLaunchProfile.Size value:AppLaunchProfile.Size.values())size.add(2,value.ordinal(),value.ordinal(),sizes[value.ordinal()]).setCheckable(true).setChecked(p.size==value).setOnMenuItemClickListener(m->{if(value==AppLaunchProfile.Size.CUSTOM)customSize(c,component);else{Profiles.setSize(c,component,value);}return true;});size.setGroupCheckable(2,true,true);
        SubMenu position=launch.addSubMenu(c.getString(R.string.ui_initial_position));String[] positions={c.getString(R.string.ui_automatic),c.getString(R.string.ui_center),c.getString(R.string.ui_last_position)};
        for(AppLaunchProfile.Position value:AppLaunchProfile.Position.values())position.add(3,value.ordinal(),value.ordinal(),positions[value.ordinal()]).setCheckable(true).setChecked(p.position==value).setOnMenuItemClickListener(m->{Profiles.setPosition(c,component,value);return true;});position.setGroupCheckable(3,true,true);
        launch.add(c.getString(R.string.ui_remember_position_and_size)).setCheckable(true).setChecked(p.rememberBounds).setOnMenuItemClickListener(m->{Profiles.setRememberBounds(c,component,!m.isChecked());return true;});
        }
        if(task!=null&&state!=null){
            addTaskActions(c,menu,task,action->{dismiss.run();state.action(task,action);});
            SubMenu window=menu.addSubMenu(1,0,11,c.getString(R.string.ui_current_window));
            boolean available=state.snapshot().canPin&&(task.mode==5||task.alwaysOnTop);
            window.add(!available?c.getString(!state.snapshot().canPin?R.string.window_pin_unsupported:R.string.window_pin_window_only):c.getString(R.string.window_pin))
                    .setCheckable(true).setChecked(task.alwaysOnTop).setEnabled(available)
                    .setOnMenuItemClickListener(m->{state.action(task,"togglePin");return true;});
        String[] labels={c.getString(R.string.ui_minimize),c.getString(R.string.ui_maximized),c.getString(R.string.ui_restore_size),c.getString(R.string.ui_snap_left),c.getString(R.string.ui_snap_right)};String[] actions={"minimize","maximize","restore","left","right"};for(int i=0;i<labels.length;i++){String action=actions[i];window.add(labels[i]).setOnMenuItemClickListener(m->{state.action(task,action);return true;});}}
        SubMenu placement=menu.addSubMenu(1,0,20,c.getString(R.string.menu_placement));
        if(move!=null)placement.add(R.string.ui_move_2).setOnMenuItemClickListener(m->{move.run();return true;});
        placement.add(Launches.desktop(c).contains(component)?c.getString(R.string.ui_remove_from_desktop):c.getString(R.string.ui_add_to_desktop)).setOnMenuItemClickListener(m->{Launches.toggleDesktop(c,component);return true;});
        placement.add(StartPins.get(c,display).contains(component)?c.getString(R.string.start_unpin):c.getString(R.string.start_pin)).setOnMenuItemClickListener(m->{StartPins.toggle(c,display,component);return true;});
        placement.add(Launches.dockPins(c).contains(component)?R.string.phone_unpin:R.string.phone_pin).setOnMenuItemClickListener(m->{Launches.toggleDockPin(c,component);return true;});
        placement.add(Launches.taskbarPins(c).contains(component)?R.string.ui_unpin_from_taskbar:R.string.ui_pin_to_taskbar).setOnMenuItemClickListener(m->{Launches.toggleTaskbarPin(c,component);return true;});
        SubMenu customize=menu.addSubMenu(1,0,30,c.getString(R.string.menu_customize));
        customize.add(R.string.icons_edit).setOnMenuItemClickListener(m->{dismiss.run();IconSettingsActivity.open(c,display,component);return true;});
        menu.add(2,0,40,c.getString(R.string.ui_app_info)).setOnMenuItemClickListener(m->{dismiss.run();try{c.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+p.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());}catch(RuntimeException e){Launches.problem(c,e.getMessage());}return true;});
        SubMenu groups=placement.addSubMenu(c.getString(R.string.launcher_assign_group));
        java.util.List<String> names=new java.util.ArrayList<>();names.add("");names.addAll(AppOrganization.groups(c));
        for(String group:names)groups.add(group.isEmpty()?c.getString(R.string.launcher_ungrouped):group).setCheckable(true).setChecked(group.equals(AppOrganization.group(c,component))).setOnMenuItemClickListener(item->{AppOrganization.assign(c,component,group);return true;});
        customize.add(c.getString(AppOrganization.hidden(c,display,component)?R.string.launcher_show_app:R.string.launcher_hide_app)).setOnMenuItemClickListener(item->{AppOrganization.hide(c,display,component,!AppOrganization.hidden(c,display,component));return true;});
        if(onDismiss!=null)popup.setOnDismissListener(ignored->onDismiss.run());
        menu.setGroupDividerEnabled(true);DesktopBackdrop.showPopup(c,popup);return popup;
    }
    static final int RETURN_TO_MAIN=21001,CLOSE_TASK=21002;
    /** Direct task commands, separate from launch/profile options. No package-wide stop. */
    static void addTaskActions(Context c,Menu menu,TaskSnapshot.Task task,java.util.function.Consumer<String> selected){
        if(TaskModes.canReturnToMain(task.mode))menu.add(0,RETURN_TO_MAIN,1,R.string.phone_sidebar_fullscreen_task)
                .setOnMenuItemClickListener(item->{selected.accept("fullscreen");return true;});
        menu.add(0,CLOSE_TASK,2,R.string.phone_sidebar_close_task)
                .setOnMenuItemClickListener(item->{selected.accept("close");return true;});
    }
    private static void customSize(Context c,String component){
        LaunchProfileSnapshot p=Profiles.snapshot(c,component);
        LinearLayout form=Ui.column(c);form.setPadding(Ui.dp(c,20),0,Ui.dp(c,20),0);
        EditText width=new EditText(c),height=new EditText(c);width.setHint(c.getString(R.string.ui_width_px));height.setHint(c.getString(R.string.ui_height_px));width.setContentDescription(c.getString(R.string.ui_initial_width));height.setContentDescription(c.getString(R.string.ui_initial_height));width.setInputType(2);height.setInputType(2);width.setText(String.valueOf(p.width));height.setText(String.valueOf(p.height));form.addView(width);form.addView(height);
        AlertDialog dialog=new AlertDialog.Builder(c).setTitle(c.getString(R.string.ui_initial_size_display_pixels)).setView(form).setNegativeButton(c.getString(R.string.ui_cancel),null).setPositiveButton(c.getString(R.string.ui_save),null).create();
        if(!(c instanceof Activity))dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{int w=Integer.parseInt(width.getText().toString()),h=Integer.parseInt(height.getText().toString());if(w<240||w>16384||h<160||h>16384)throw new NumberFormatException();Profiles.setCustomSize(c,component,w,h);dialog.dismiss();}catch(NumberFormatException e){width.setError(c.getString(R.string.ui_width_must_be_240_16384_and_height_160_16384));}}));
        if(c instanceof Activity)DesktopBackdrop.showDialog((Activity)c,dialog);else dialog.show();
    }
}
