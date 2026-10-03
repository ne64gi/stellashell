package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.*;
import android.content.pm.*;
import android.os.Bundle;

/** Real PackageManager resolution; synthetic entries, isolated preferences, no personal app content. */
final class LauncherEntryChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void run(Instrumentation test,String launchComponent)throws Exception{
        Context base=test.getTargetContext();String pkg=test.getContext().getPackageName();
        String first=pkg+"/net.fuyumori.stellashell.LauncherEntryFirst",second=pkg+"/net.fuyumori.stellashell.LauncherEntrySecond";
        check(Launches.normalAppIntent(base,first).getComponent().equals(ComponentName.unflattenFromString(first)),"First launcher entry changed");
        check(Launches.normalAppIntent(base,second).getComponent().equals(ComponentName.unflattenFromString(second)),"Explicit second launcher alias changed");
        for(String name:new String[]{"LauncherEntryPrivate","HomeLaunchProbeActivity","LauncherEntryProtected","RemovedLauncher"}){
            Intent resolved=Launches.normalAppIntent(base,pkg+"/net.fuyumori.stellashell."+name);
            ActivityInfo info=base.getPackageManager().getActivityInfo(resolved.getComponent(),0);
            check(info.exported&&info.permission==null,"Internal/stale/protected entry did not use a public launcher");
            check(resolved.getComponent().equals(ComponentName.unflattenFromString(first))||resolved.getComponent().equals(ComponentName.unflattenFromString(second)),"Fallback left the fixture launcher entries");
        }
        boolean absent=false;try{Launches.normalAppIntent(base,"fixture.missing.launcher/.Main");}catch(ActivityNotFoundException expected){absent=true;}check(absent,"Missing package silently launched another app");
        Intent[] sent={null};Bundle[] options={null};
        Context captured=new ContextWrapper(base){
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences("launcher_entry_checks",mode);}
            @Override public void startActivity(Intent intent,Bundle bundle){sent[0]=new Intent(intent);options[0]=bundle;}
        };
        try{
            Launches.prefs(captured).edit().clear().commit();
            Launches.normalApp(captured,pkg+"/net.fuyumori.stellashell.LauncherEntryPrivate");
            check(sent[0]!=null&&sent[0].getComponent()!=null&&options[0]!=null,"Normal app launch did not submit resolved entry");
            int flags=Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED;
            check((sent[0].getFlags()&flags)==flags,"Standard launch flags lost");
            check(Launches.recents(captured).size()==1,"Normal app recent entry lost");
        }finally{Launches.prefs(captured).edit().clear().commit();}
        if(launchComponent!=null){
            Intent resolved=Launches.normalAppIntent(base,launchComponent);
            ActivityInfo info=base.getPackageManager().getActivityInfo(resolved.getComponent(),0);
            check(info.exported&&(info.permission==null||base.checkSelfPermission(info.permission)==PackageManager.PERMISSION_GRANTED),"Requested app launcher is inaccessible");
            check(resolved.getComponent().getPackageName().equals(ComponentName.unflattenFromString(launchComponent).getPackageName()),"Requested app fallback changed package");
            Throwable[] error={null};test.runOnMainSync(()->{try{Launches.normalApp(base,launchComponent);}catch(Throwable failure){error[0]=failure;}});
            if(error[0]!=null)throw new AssertionError("Requested normal app launch was rejected",error[0]);
        }
    }
}
