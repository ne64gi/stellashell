package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.fuyumori.stellashell.feature.launch.LaunchItems;
import net.fuyumori.stellashell.feature.launch.LaunchItems.Profile;
import net.fuyumori.stellashell.feature.launch.LaunchItems.Surface;
import net.fuyumori.stellashell.feature.launch.LaunchItems.ToggleResult;

/** Nonce launch-item persistence only. Real PackageManager routing stays in LauncherEntryChecks. */
final class LauncherFeatureChecks {
    private static String component(int index){return "fixture.launch.items/fixture.launch.items.Entry"+index;}
    static void run(Instrumentation test)throws Exception {
        Context actual=test.getTargetContext();String file="launcher_feature_fixture_"+UUID.randomUUID();
        SharedPreferences preferences=actual.getSharedPreferences(file,Context.MODE_PRIVATE);
        LaunchItems first=new LaunchItems(preferences),second=new LaunchItems(preferences);Throwable[] failure={null};
        try{
            test.runOnMainSync(()->{
                try{
                    preferences.edit().putString("unrelated","retain").putInt("runtime_nonce",19).commit();
                    Map<String,?> before=preferences.getAll();
                    LaunchItems.Snapshot empty=first.snapshot(Profile.PHONE);
                    check(empty.dockPins.isEmpty()&&empty.taskbarPins.isEmpty()&&empty.recent.isEmpty()&&empty.desktop.isEmpty(),"Missing launch items must be empty");
                    check(preferences.getAll().equals(before),"Read persisted launch defaults");
                    preferences.edit().putString("phone_pinned",component(0)).putString("pinned",component(1))
                            .putString("phone_taskbar_pinned",component(2)).putString("dock_pinned",component(3))
                            .putString("phone_desktop_shortcuts",component(4)).putString("desktop_shortcuts",component(5))
                            .putString("recent",component(6)+"\n"+component(7)+"\n").commit();
                    before=preferences.getAll();LaunchItems.Snapshot phone=first.snapshot(Profile.PHONE),desktop=first.snapshot(Profile.DESKTOP);
                    check(phone.dockPins.equals(Arrays.asList(component(0)))&&phone.taskbarPins.equals(Arrays.asList(component(2))),"Legacy phone pin keys changed");
                    check(desktop.dockPins.equals(Arrays.asList(component(3)))&&desktop.taskbarPins.equals(Arrays.asList(component(1))),"Legacy desktop pin keys changed");
                    check(phone.desktop.equals(Arrays.asList(component(4)))&&desktop.desktop.equals(Arrays.asList(component(5))),"Profile desktop keys changed");
                    check(phone.recent.equals(Arrays.asList(component(6),component(7)))&&phone.recent.equals(desktop.recent),"Shared recent key or newline/trailing split changed");
                    check(preferences.getAll().equals(before),"Read migrated existing launch items");
                    for(List<String> list:Arrays.asList(phone.dockPins,phone.taskbarPins,phone.recent,phone.desktop,first.shortcuts(Profile.PHONE),first.pins(Profile.PHONE,Surface.DOCK),first.recents(),first.desktop(Profile.PHONE))){
                        try{list.add(component(99));throw new AssertionError("Launch snapshot/list was mutable");}catch(UnsupportedOperationException expected){}
                    }
                    second.togglePin(Profile.PHONE,Surface.DOCK,component(8));
                    check(phone.dockPins.equals(Arrays.asList(component(0))),"Retained snapshot mutated after another owner command");
                    check(first.pins(Profile.PHONE,Surface.DOCK).equals(Arrays.asList(component(0),component(8))),"Owner retained stale pin cache");
                    preferences.edit().remove("phone_pinned").remove("pinned").remove("phone_taskbar_pinned").remove("dock_pinned").commit();
                    for(Profile profile:Profile.values()){
                        for(int i=0;i<6;i++)check(first.togglePin(profile,Surface.TASKBAR,component(i))==ToggleResult.ADDED,"Taskbar slot rejected before six");
                        before=preferences.getAll();check(second.togglePin(profile,Surface.TASKBAR,component(6))==ToggleResult.LIMIT_REACHED,"Taskbar accepted seventh pin");
                        check(preferences.getAll().equals(before),"Limit-reached command changed persistence");
                        check(second.togglePin(profile,Surface.TASKBAR,component(2))==ToggleResult.REMOVED,"Full taskbar could not remove a pin");
                        check(first.togglePin(profile,Surface.TASKBAR,component(6))==ToggleResult.ADDED,"Freed taskbar slot remained unavailable");
                        for(int i=0;i<8;i++)check(first.togglePin(profile,Surface.DOCK,component(i))==ToggleResult.ADDED,"Dock incorrectly inherited six-slot limit");
                    }
                    second.togglePin(Profile.PHONE,Surface.DOCK,component(0));
                    check(first.pins(Profile.PHONE,Surface.DOCK).size()==7&&first.pins(Profile.DESKTOP,Surface.DOCK).size()==8,"Phone/Desktop Dock pins were linked");
                    check(first.pins(Profile.PHONE,Surface.TASKBAR).size()==6&&first.pins(Profile.DESKTOP,Surface.TASKBAR).size()==6,"Dock command changed Taskbar pins");
                    preferences.edit().remove("recent").commit();for(int i=0;i<7;i++)first.remember(component(i));second.remember(component(0));
                    List<String> recent=Arrays.asList(component(0),component(6),component(5),component(4),component(3),component(2));
                    check(first.recents().equals(recent)&&first.snapshot(Profile.PHONE).recent.equals(first.snapshot(Profile.DESKTOP).recent),"Recent order/dedup/six-slot/shared contract changed");
                    preferences.edit().putString("phone_pinned",component(2)).putString("pinned",component(3))
                            .putString("phone_taskbar_pinned",component(5)).putString("dock_pinned",component(4))
                            .putString("recent",component(3)+"\n"+component(2)+"\n"+component(1)).commit();
                    check(first.shortcuts(Profile.PHONE).equals(Arrays.asList(component(2),component(3),component(1))),"Phone shortcuts must use legacy Dock pins then recent union");
                    check(first.shortcuts(Profile.DESKTOP).equals(Arrays.asList(component(3),component(2),component(1))),"Desktop shortcuts must use legacy Taskbar pins then recent union");
                    preferences.edit().remove("phone_desktop_shortcuts").remove("desktop_shortcuts").commit();
                    String group="group:"+UUID.randomUUID();first.toggleDesktop(Profile.PHONE,group);second.toggleDesktop(Profile.PHONE,component(0));first.toggleDesktop(Profile.DESKTOP,component(1));
                    check(first.desktop(Profile.PHONE).equals(Arrays.asList(group,component(0)))&&first.desktop(Profile.DESKTOP).equals(Arrays.asList(component(1))),"Validated group/component desktop placement or profile independence changed");
                    second.toggleDesktop(Profile.PHONE,group);check(first.desktop(Profile.PHONE).equals(Arrays.asList(component(0))),"Desktop toggle did not remove placement");
                    preferences.edit().putString("phone_desktop_shortcuts",group+"\n"+component(0)+"\n"+group)
                            .putString("desktop_shortcuts",component(1)+"\n"+group+"\n"+group).commit();
                    LaunchItems.Snapshot oldPhone=first.snapshot(Profile.PHONE),oldDesktop=first.snapshot(Profile.DESKTOP);
                    second.removeDesktopReference(group);
                    check(first.desktop(Profile.PHONE).equals(Arrays.asList(component(0),group))&&first.desktop(Profile.DESKTOP).equals(Arrays.asList(component(1),group)),"Reference cleanup must remove only the first matching item in each profile");
                    check(oldPhone.desktop.equals(Arrays.asList(group,component(0),group))&&oldDesktop.desktop.equals(Arrays.asList(component(1),group,group)),"Reference cleanup mutated retained desktop snapshots");
                    preferences.edit().remove("phone_desktop_shortcuts").putString("desktop_shortcuts",group).commit();
                    first.removeDesktopReference(group);
                    check(!preferences.contains("phone_desktop_shortcuts")&&first.desktop(Profile.DESKTOP).isEmpty(),"Cleanup created absent phone key or retained present external reference");
                    preferences.edit().remove("desktop_shortcuts").putString("phone_desktop_shortcuts",group).commit();
                    second.removeDesktopReference(group);
                    check(!preferences.contains("desktop_shortcuts")&&first.desktop(Profile.PHONE).isEmpty(),"Cleanup created absent external key or retained present phone reference");
                    preferences.edit().remove("phone_desktop_shortcuts").commit();before=preferences.getAll();
                    first.removeDesktopReference("unknown-reference");
                    check(preferences.getAll().equals(before),"Absent reference cleanup created keys or rewrote unrelated values");
                    preferences.edit().putString("desktop_shortcuts","legacy-corrupt-reference\n"+component(1)).commit();
                    second.removeDesktopReference("legacy-corrupt-reference");
                    check(first.desktop(Profile.DESKTOP).equals(Arrays.asList(component(1))),"Cleanup must accept arbitrary stored text without component validation");
                    preferences.edit().putString("desktop_shortcuts",group).putInt("phone_desktop_shortcuts",42).commit();before=preferences.getAll();
                    try{first.removeDesktopReference(group);throw new AssertionError("Wrong-typed legacy preferences were silently coerced");}catch(ClassCastException expected){}
                    check(preferences.getAll().equals(before),"Failed legacy string cast partially applied reference deletion");
                    Context placementContext=new ContextWrapper(actual){
                        @Override public SharedPreferences getSharedPreferences(String name,int mode){return preferences;}
                    };
                    preferences.edit().putString("start_pinned",group).putString("phone_start_pinned",group).commit();
                    before=preferences.getAll();
                    try{GroupEntries.removeReferences(placementContext,group.substring(6));throw new AssertionError("Group cleanup ignored wrong-typed desktop storage");}catch(ClassCastException expected){}
                    check(preferences.getAll().equals(before),"Failed desktop owner cleanup partially removed Start placement");
                    preferences.edit().remove("phone_desktop_shortcuts").commit();
                    preferences.edit().putString("phone_desktop_shortcuts",group).commit();
                    GroupEntries.removeReferences(placementContext,group.substring(6));
                    check(preferences.getString("start_pinned","").isEmpty()&&preferences.getString("phone_start_pinned","").isEmpty()
                            &&first.desktop(Profile.PHONE).isEmpty()&&first.desktop(Profile.DESKTOP).isEmpty(),"Group cleanup did not coordinate Start and desktop owners");
                    before=preferences.getAll();
                    for(String invalid:new String[]{"invalid","fixture.launch.items/invalid;component","fixture.launch.items/.Main\nother"}){
                        try{first.togglePin(Profile.PHONE,Surface.DOCK,invalid);throw new AssertionError("Invalid pin component accepted");}catch(IllegalArgumentException expected){}
                        try{second.remember(invalid);throw new AssertionError("Invalid recent component accepted");}catch(IllegalArgumentException expected){}
                    }
                    check(preferences.getAll().equals(before),"Rejected component changed persistence");
                    concurrentPinCommands(preferences,first,second);
                    check(preferences.getString("unrelated","").equals("retain")&&preferences.getInt("runtime_nonce",0)==19,"Launch commands rewrote unrelated keys");
                }catch(Throwable error){failure[0]=error;}
            });
            if(failure[0]!=null)throw new AssertionError(failure[0]);
        }finally{test.runOnMainSync(()->actual.deleteSharedPreferences(file));}
    }
    private static void concurrentPinCommands(SharedPreferences preferences,LaunchItems first,LaunchItems second)throws Exception {
        preferences.edit().remove("phone_pinned").commit();LaunchItems.Snapshot old=first.snapshot(Profile.PHONE);
        CountDownLatch start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<?> one=pool.submit(()->{await(start);for(int i=100;i<130;i++)first.togglePin(Profile.PHONE,Surface.DOCK,component(i));});
            Future<?> two=pool.submit(()->{await(start);for(int i=130;i<160;i++)second.togglePin(Profile.PHONE,Surface.DOCK,component(i));});
            start.countDown();one.get(10,TimeUnit.SECONDS);two.get(10,TimeUnit.SECONDS);
            List<String> current=first.pins(Profile.PHONE,Surface.DOCK);
            check(current.size()==60&&new HashSet<>(current).size()==60,"Separate owners lost concurrent pin updates");
            for(int i=100;i<160;i++)check(current.contains(component(i)),"Concurrent pin update disappeared");
            check(old.dockPins.isEmpty(),"Concurrent update mutated old snapshot");
        }finally{start.countDown();pool.shutdownNow();}
    }
    private static void await(CountDownLatch start){
        try{if(!start.await(10,TimeUnit.SECONDS))throw new AssertionError("Parallel start timeout");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }
    private static void check(boolean ok,String note){if(!ok)throw new AssertionError(note);}
}
