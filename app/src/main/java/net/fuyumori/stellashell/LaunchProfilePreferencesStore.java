package net.fuyumori.stellashell;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.fuyumori.stellashell.core.launch.LaunchProfileSnapshot;
import net.fuyumori.stellashell.core.launch.LaunchProfileStore;

/** Android-only persistence adapter. No cached mutable profile escapes. */
final class LaunchProfilePreferencesStore implements LaunchProfileStore {
    private final SharedPreferences preferences;
    private final BooleanSupplier freeformDefault;
    LaunchProfilePreferencesStore(SharedPreferences preferences,BooleanSupplier freeformDefault) {
        this.preferences=preferences;this.freeformDefault=freeformDefault;
    }
    static LaunchProfilePreferencesStore of(Context context) {
        Context application=context.getApplicationContext();
        return new LaunchProfilePreferencesStore(application.getSharedPreferences("launch_profiles",Context.MODE_PRIVATE),
                ()->Launches.prefs(application).getBoolean("freeform",false));
    }
    static String key(String component) {
        ComponentName name=ComponentName.unflattenFromString(component);
        if(name==null)throw new IllegalArgumentException("Invalid component");
        return name.flattenToString();
    }
    @Override public Object lockIdentity() {return preferences;}
    @Override public Set<String> storedComponents() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(preferences.getAll().keySet()));
    }
    @Override public boolean contains(String component) {return preferences.contains(key(component));}
    @Override public LaunchProfileSnapshot read(String component) {
        component=key(component);
        String value="{}";
        try{value=preferences.getString(component,"{}");}catch(ClassCastException ignored){}
        return LaunchProfileCodec.decode(component,value,freeformDefault.getAsBoolean());
    }
    @Override public void write(String component,LaunchProfileSnapshot profile) {
        component=key(component);
        String value=LaunchProfileCodec.encode(profile);
        if(!value.equals(preferences.getString(component,"")))preferences.edit().putString(component,value).apply();
    }
}
