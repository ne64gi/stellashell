package net.fuyumori.stellashell;

import android.app.*;
import android.os.*;
import android.content.SharedPreferences;
import java.util.*;

public final class ShellApplication extends Application implements Application.ActivityLifecycleCallbacks,SharedPreferences.OnSharedPreferenceChangeListener {
    private final Set<Activity> activities=Collections.newSetFromMap(new WeakHashMap<>());
    @Override public void onCreate(){super.onCreate();Appearance.load(this);registerActivityLifecycleCallbacks(this);Launches.prefs(this).registerOnSharedPreferenceChangeListener(this);}
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key){if(!Appearance.KEY.equals(key))return;Appearance.load(this);new Handler(Looper.getMainLooper()).post(()->{for(Activity a:new ArrayList<>(activities))if(!(a instanceof AppearanceActivity)&&!a.isFinishing()&&!a.isDestroyed())a.recreate();});}
    @Override public void onActivityPreCreated(Activity a,Bundle b){boolean panel=a instanceof HubActivity||a instanceof QuickSettingsActivity;boolean light=Appearance.theme()==R.style.AppThemeLight;a.setTheme(panel?(light?R.style.HubThemeLight:R.style.HubTheme):Appearance.theme());}
    public void onActivityCreated(Activity a,Bundle b){activities.add(a);}
    public void onActivityStarted(Activity a){if(a instanceof AppearanceActivity)return;Appearance.fonts(a.getWindow().getDecorView());}
    public void onActivityResumed(Activity a){}
    public void onActivityPaused(Activity a){}
    public void onActivityStopped(Activity a){}
    public void onActivitySaveInstanceState(Activity a,Bundle b){}
    public void onActivityDestroyed(Activity a){activities.remove(a);}
}
