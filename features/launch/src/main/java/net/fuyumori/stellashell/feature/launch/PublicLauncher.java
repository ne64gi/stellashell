package net.fuyumori.stellashell.feature.launch;

import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.Objects;
import net.fuyumori.stellashell.core.launch.Policy;

/** Public Android launcher resolution, distinct from privileged task/window routing. */
public final class PublicLauncher {
    private final Context context;
    public PublicLauncher(Context context){this.context=Objects.requireNonNull(context);}
    /** Keep explicit accessible launcher aliases; only internal/stale entries fall back within package. */
    public Intent intent(String component){
        Policy.component(component);
        ComponentName requested=ComponentName.unflattenFromString(component);
        PackageManager pm=context.getPackageManager();
        Intent query=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(requested.getPackageName());
        ComponentName firstAccessible=null;
        for(ResolveInfo entry:pm.queryIntentActivities(query,0)){
            ActivityInfo activity=entry.activityInfo;
            if(activity==null||!accessible(activity))continue;
            ComponentName launcher=new ComponentName(activity.packageName,activity.name);
            if(requested.equals(launcher))return new Intent(query).setComponent(requested);
            if(firstAccessible==null)firstAccessible=launcher;
        }
        Intent fallback=pm.getLaunchIntentForPackage(requested.getPackageName());
        if(fallback!=null&&fallback.getComponent()!=null)try{
            if(accessible(pm.getActivityInfo(fallback.getComponent(),0)))return new Intent(fallback);
        }catch(PackageManager.NameNotFoundException ignored){}
        if(firstAccessible!=null)return new Intent(query).setComponent(firstAccessible);
        throw new ActivityNotFoundException("No accessible launcher for "+requested.getPackageName());
    }
    private boolean accessible(ActivityInfo activity){
        return activity.enabled&&activity.applicationInfo!=null&&activity.applicationInfo.enabled
                &&(activity.exported||activity.applicationInfo.uid==android.os.Process.myUid())
                &&(activity.permission==null||context.checkSelfPermission(activity.permission)==PackageManager.PERMISSION_GRANTED);
    }
    /** Successful submission only; recents and display validation are caller-owned decisions. */
    public void launch(String component,int displayId){
        Intent target=intent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        context.startActivity(target,ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());
    }
}
