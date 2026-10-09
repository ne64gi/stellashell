package net.fuyumori.stellashell.feature.launch;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.content.res.Configuration;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-scoped launcher metadata. Never retains an Activity, View, or a full icon catalog. */
public final class AppCatalog {
    public static final class Entry {
        public final String component, label;
        /** Icons are loaded separately, only when a consumer needs one. */
        public final Drawable icon;
        private Entry(String component,String label,Drawable icon){this.component=component;this.label=label;this.icon=icon;}
    }
    private static final Map<String,CatalogCache<Entry>> catalogs=new LinkedHashMap<>();
    private static final Map<String,Drawable.ConstantState> icons=new LinkedHashMap<>(64,.75f,true);
    private static final ExecutorService warmer=Executors.newSingleThreadExecutor();
    private static long iconGeneration;
    private final Context context;
    public AppCatalog(Context context){this.context=Objects.requireNonNull(context);}
    private CatalogCache<Entry> cache(){
        Configuration configuration=context.getResources().getConfiguration();
        String key=context.getPackageName()+":"+configuration.getLocales().toLanguageTags();
        synchronized(catalogs){
            CatalogCache<Entry> cache=catalogs.get(key);
            if(cache==null){
                Context application=context.getApplicationContext();
                Context source=(application==null?context:application).createConfigurationContext(new Configuration(configuration));
                cache=new CatalogCache<>(()->read(source));
                if(catalogs.size()>=2)catalogs.remove(catalogs.keySet().iterator().next());
                catalogs.put(key,cache);
            }
            return cache;
        }
    }
    /** Main-thread safe: no package scan, icon decoding, or waiting. Null means cold. */
    public List<Entry> cachedEntries(){return cache().peek();}
    /** Background-thread operation; concurrent opens share a single scan. */
    public List<Entry> entries(){return cache().get();}
    /** Finite startup event, not a periodic background refresh. */
    public void warm(){CatalogCache<Entry> cache=cache();warmer.execute(()->{try{cache.get();}catch(RuntimeException ignored){}});}
    public static void invalidate(){
        synchronized(catalogs){for(CatalogCache<Entry> cache:catalogs.values())cache.invalidate();}
        synchronized(icons){iconGeneration++;icons.clear();}
    }
    /** Small LRU of drawable states, not drawables shared between Views. */
    public static Drawable icon(Context context,String component){
        String key=context.getResources().getConfiguration().getLocales().toLanguageTags()+":"
                +context.getResources().getConfiguration().densityDpi+":"+component;
        long generation;
        synchronized(icons){
            generation=iconGeneration;
            Drawable.ConstantState state=icons.get(key);
            if(state!=null)return state.newDrawable(context.getResources()).mutate();
        }
        Drawable drawable;
        try{drawable=context.getPackageManager().getActivityIcon(ComponentName.unflattenFromString(component));}
        catch(PackageManager.NameNotFoundException|RuntimeException error){drawable=context.getPackageManager().getDefaultActivityIcon();}
        Drawable.ConstantState state=drawable.getConstantState();
        if(state!=null)synchronized(icons){
            if(generation==iconGeneration){
                if(icons.size()>=64)icons.remove(icons.keySet().iterator().next());
                icons.put(key,state);
            }
        }
        return drawable.mutate();
    }
    private static List<Entry> read(Context context){
        PackageManager pm=context.getPackageManager();List<Entry> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        Intent query=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for(ResolveInfo info:pm.queryIntentActivities(query,0)){
            if(info.activityInfo==null||context.getPackageName().equals(info.activityInfo.packageName))continue;
            ComponentName component=new ComponentName(info.activityInfo.packageName,info.activityInfo.name);
            if(seen.add(component.flattenToString()))out.add(new Entry(component.flattenToString(),info.loadLabel(pm).toString(),null));
        }
        Collator sorter=Collator.getInstance();out.sort((a,b)->sorter.compare(a.label,b.label));
        return Collections.unmodifiableList(out);
    }
}
