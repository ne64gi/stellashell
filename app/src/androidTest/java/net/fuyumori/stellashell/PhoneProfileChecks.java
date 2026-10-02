package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;
import java.util.*;

/** Isolated preferences and captured launches: never opens a personal app or edits its layout. */
final class PhoneProfileChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void run(Instrumentation test,int external){
        Context base=test.getTargetContext();Set<String> stores=new HashSet<>();String prefix="phone_profile_fixture_";
        class Capture extends ContextWrapper {
            final Display display;Intent launched;Bundle options;
            Capture(int id){super(base);display=base.getSystemService(DisplayManager.class).getDisplay(id);if(display==null)throw new AssertionError("missing fixture display");}
            @Override public Display getDisplay(){return display;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){stores.add(name);return base.getSharedPreferences(prefix+name,mode);}
            @Override public void startActivity(Intent intent,Bundle opts){launched=intent;options=opts;}
        }
        Capture phone=new Capture(0),desktop=new Capture(external);
        try {
            SharedPreferences prefs=Launches.prefs(phone);prefs.edit().clear().putString("pinned","a.b/a.b.One").putString("start_pinned","a.b/a.b.One").putString("desktop_shortcuts","a.b/a.b.One").putBoolean("wallpaper_fit",true).commit();
            phone.getSharedPreferences("shortcut_positions",0).edit().putString("a.b/a.b.One","{\"x\":42,\"y\":96}").commit();
            WorkspaceProfile.initialize(phone);
            check(WorkspaceProfile.phone(phone)&&!WorkspaceProfile.phone(desktop),"profile follows display");
            check(Launches.pins(phone).equals(Launches.pins(desktop)),"first migration must copy pins");
            Launches.togglePin(phone,"a.b/a.b.Two");
            check(Launches.pins(phone).size()==2&&Launches.pins(desktop).size()==1,"phone pin leaked into desktop");
            Launches.togglePin(desktop,"a.b/a.b.Three");WorkspaceProfile.initialize(phone);
            check(!Launches.pins(phone).contains("a.b/a.b.Three"),"migration repeated");
            StartPins.toggle(phone,"a.b/a.b.Two");check(!StartPins.get(desktop).contains("a.b/a.b.Two"),"start pins leaked");
            Launches.toggleDesktop(phone,"a.b/a.b.Two");check(!Launches.desktop(desktop).contains("a.b/a.b.Two"),"shortcuts leaked");
            check(phone.getSharedPreferences("phone_shortcut_positions",0).getString("a.b/a.b.One","").contains("42"),"positions were not migrated");
            prefs.edit().putBoolean(WorkspaceProfile.key(phone,"wallpaper_fit"),false).putFloat(WorkspaceProfile.key(phone,"wallpaper_x"),.65f).commit();
            check(prefs.getBoolean(WorkspaceProfile.key(desktop,"wallpaper_fit"),false),"wallpaper mode leaked");
            check(!prefs.contains(WorkspaceProfile.key(desktop,"wallpaper_x")),"focal point leaked");
            check(Launches.basicHome(phone,0)&&!Launches.basicHome(desktop,external),"standard launch boundary");
            Launches.app(phone,"a.b/a.b.One",0);
            check(phone.launched!=null&&Intent.ACTION_MAIN.equals(phone.launched.getAction()),"normal launch did not use Android intent");
            Bundle expected=ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle();
            for(String key:expected.keySet())check(java.util.Objects.equals(expected.get(key),phone.options.get(key)),"normal launch options mismatch: "+key);
            phone.launched=null;Launches.home(phone,0);
            check(phone.launched!=null&&phone.launched.hasCategory(Intent.CATEGORY_HOME),"home must use Android HOME without minimizing tasks");
            prefs.edit().putBoolean("primary_mode",true).putBoolean("compact_workspace",true).putInt("workspace_display",0).commit();
            boolean[] transferred={false};Workspace.transfer(phone,external,()->transferred[0]=true);
            check(transferred[0]&&Workspace.target(phone)==external,"empty standard workspace handoff required a task bridge");
            check(WorkspaceProfile.phone(phone),"Phone profile changed with shell destination");
            Workspace.transfer(phone,0,()->transferred[0]=false);
            check(!transferred[0]&&Workspace.target(phone)==0,"Phone return failed");
            prefs.edit().putBoolean("phone_window_management",true).commit();
            check(WorkspaceProfile.standard(phone,0),"legacy management flag changed normal fullscreen launch");
            AppOrganization.addGroup(phone,"Tools");String folder=GroupEntries.reference(phone,"Tools");
            StartPins.toggle(phone,folder);Launches.toggleDesktop(desktop,folder);
            check(StartPins.get(phone).contains(folder)&&!StartPins.get(desktop).contains(folder),"group Start pin leaked between profiles");
            check(Launches.desktop(desktop).contains(folder)&&!Launches.desktop(phone).contains(folder),"group shortcut leaked between profiles");
            desktop.getSharedPreferences("shortcut_positions",0).edit().putString(folder,"{\"x\":64,\"y\":128}").commit();
            AppOrganization.renameGroup(phone,"Tools","Renamed");
            check("Renamed".equals(GroupEntries.name(desktop,folder)),"rename broke stable group reference");
            check(GroupEntries.reference(phone,"Renamed").equals(folder),"rename changed folder identity");
            check(StartPins.get(phone).contains(folder)&&Launches.desktop(desktop).contains(folder),"rename lost group placement");
            check(desktop.getSharedPreferences("shortcut_positions",0).getString(folder,"").contains("128"),"rename lost group position");
            AppOrganization.renameGroup(phone,"Renamed","");
            check(GroupEntries.name(phone,folder)==null&&!StartPins.get(phone).contains(folder)&&!Launches.desktop(desktop).contains(folder),"deleted group retained links");
            check(!desktop.getSharedPreferences("shortcut_positions",0).contains(folder),"deleted group retained position");
            check(StartPins.get(phone).contains("a.b/a.b.Two"),"group deletion removed unrelated app pin");
        } finally {for(String name:stores)base.deleteSharedPreferences(prefix+name);}
    }
}
