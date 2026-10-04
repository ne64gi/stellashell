package net.fuyumori.stellashell.feature.launch;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Fresh PackageManager launcher catalog. No View, app singleton, or retained catalog cache. */
public final class AppCatalog {
    public static final class Entry {
        public final String component, label;
        /** Android drawable reference, not a claim that the Drawable itself is immutable. */
        public final Drawable icon;
        private Entry(String component,String label,Drawable icon){this.component=component;this.label=label;this.icon=icon;}
    }
    private final Context context;
    public AppCatalog(Context context){this.context=Objects.requireNonNull(context);}
    public List<Entry> entries(){
        PackageManager pm=context.getPackageManager();List<Entry> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        Intent query=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for(ResolveInfo info:pm.queryIntentActivities(query,0)){
            if(info.activityInfo==null||context.getPackageName().equals(info.activityInfo.packageName))continue;
            ComponentName component=new ComponentName(info.activityInfo.packageName,info.activityInfo.name);
            if(seen.add(component.flattenToString()))out.add(new Entry(component.flattenToString(),info.loadLabel(pm).toString(),info.loadIcon(pm)));
        }
        Collator sorter=Collator.getInstance();out.sort((a,b)->sorter.compare(a.label,b.label));
        return Collections.unmodifiableList(out);
    }
}
