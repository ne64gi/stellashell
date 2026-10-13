package net.fuyumori.stellashell.feature.launch;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
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
import java.io.File;
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
    private static final ExecutorService warmer=Executors.newSingleThreadExecutor();
    private static LabelCache labels;
    private final Context context;
    public AppCatalog(Context context){this.context=Objects.requireNonNull(context);}
    private CatalogCache<Entry> cache(){
        Configuration configuration=context.getResources().getConfiguration();
        String key=context.getPackageName()+":"+configurationKey(configuration);
        synchronized(catalogs){
            CatalogCache<Entry> cache=catalogs.get(key);
            if(cache==null){
                Context application=context.getApplicationContext();
                Context source=(application==null?context:application).createConfigurationContext(new Configuration(configuration));
                if(labels==null)labels=new LabelCache(new File(source.getNoBackupFilesDir(),"launcher-labels-v1.bin"));
                LabelCache store=labels;
                cache=new CatalogCache<>(()->read(source,store));
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
        invalidatePackage(null);
    }
    /** Package broadcasts may preserve scalar labels belonging to unchanged packages. */
    public static void invalidatePackage(String packageName){
        synchronized(catalogs){
            if(labels!=null)labels.invalidate(packageName);
            for(CatalogCache<Entry> cache:catalogs.values())cache.invalidate();
        }
    }
    /** Uncached source artwork. Consumers own any cache of final rendered icons. */
    public static Drawable icon(Context context,String component){
        PackageManager pm=context.getPackageManager();ComponentName name=null;
        Drawable drawable;
        try{name=ComponentName.unflattenFromString(component);drawable=pm.getActivityIcon(name);}
        catch(PackageManager.NameNotFoundException|RuntimeException error){
            try{if(name==null)throw new IllegalArgumentException();drawable=pm.getApplicationIcon(name.getPackageName());}
            catch(PackageManager.NameNotFoundException|RuntimeException unavailable){drawable=pm.getDefaultActivityIcon();}
        }
        return drawable.mutate();
    }
    private static String configurationKey(Configuration c){
        return Build.FINGERPRINT+":"+c.getLocales().toLanguageTags()+":"+c.mcc+":"+c.mnc+":"+Float.floatToIntBits(c.fontScale)
                +":"+c.touchscreen+":"+c.keyboard+":"+c.keyboardHidden+":"+c.hardKeyboardHidden+":"+c.navigation+":"+c.navigationHidden
                +":"+c.orientation+":"+c.colorMode+":"+c.screenLayout+":"+c.uiMode+":"+c.screenWidthDp+":"+c.screenHeightDp
                +":"+c.smallestScreenWidthDp+":"+c.densityDpi+":"+(Build.VERSION.SDK_INT>=31?c.fontWeightAdjustment:0)
                +":"+(Build.VERSION.SDK_INT>=34?c.getGrammaticalGender():0);
    }
    private static List<Entry> read(Context context,LabelCache store){
        long request=store.beginRefresh();
        PackageManager pm=context.getPackageManager();List<Entry> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        List<LabelCache.Item> current=new ArrayList<>();Map<String,String> identities=new LinkedHashMap<>();
        Intent query=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for(ResolveInfo info:pm.queryIntentActivities(query,0)){
            if(info.activityInfo==null||context.getPackageName().equals(info.activityInfo.packageName))continue;
            ComponentName component=new ComponentName(info.activityInfo.packageName,info.activityInfo.name);
            String name=component.flattenToString(),pkg=info.activityInfo.packageName;
            if(!seen.add(name))continue;
            if(!identities.containsKey(pkg)){
                String identity=null;
                try{PackageInfo version=pm.getPackageInfo(pkg,0);identity=pkg+":"+version.getLongVersionCode()+":"+version.lastUpdateTime;}
                catch(PackageManager.NameNotFoundException|RuntimeException ignored){}
                identities.put(pkg,identity);
            }
            current.add(new LabelCache.Item(name,identities.get(pkg),()->info.loadLabel(pm).toString()));
        }
        for(Map.Entry<String,String> entry:store.resolve(request,configurationKey(context.getResources().getConfiguration()),current).entrySet())
            out.add(new Entry(entry.getKey(),entry.getValue(),null));
        Collator sorter=Collator.getInstance();out.sort((a,b)->sorter.compare(a.label,b.label));
        return Collections.unmodifiableList(out);
    }
}
