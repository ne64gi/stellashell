package net.fuyumori.stellashell;

import android.content.*;
import android.content.pm.*;
import android.content.res.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.util.*;

/** Local icon overrides. Pack artwork is preserved, including intentional backgrounds. */
// Third-party drawable names are only known at runtime, not in our generated R class.
@android.annotation.SuppressLint("DiscouragedApi")
final class IconTheme {
    static final String PACK="icons_pack", REVISION="icons_revision", PREFIX="icons_app:";
    static final String[] ACTIONS={"org.adw.launcher.THEMES","com.gau.go.launcherex.theme","com.novalauncher.THEME"};
    private static final Map<String,Pack> cache=new LinkedHashMap<>();
    private static long packGeneration;
    static final class Pack {
        final String name;final Resources resources;
        final Map<String,String> mappings=new LinkedHashMap<>();final Set<String> icons=new LinkedHashSet<>();
        Pack(String name,Resources resources){this.name=name;this.resources=resources;}
        Drawable drawable(String name){try{int id=resources.getIdentifier(name,"drawable",this.name);if(id==0)id=resources.getIdentifier(name,"mipmap",this.name);return id==0?null:resources.getDrawable(id,null).mutate();}catch(RuntimeException e){return null;}}
    }
    static final class Choice {
        final String pkg,label;
        Choice(String pkg,String label){this.pkg=pkg;this.label=label;}
    }
    static List<Choice> installed(Context c){
        PackageManager pm=c.getPackageManager();Map<String,Choice> found=new LinkedHashMap<>();
        for(String action:ACTIONS)for(ResolveInfo info:pm.queryIntentActivities(new Intent(action),0)){
            if(info.activityInfo==null)continue;String pkg=info.activityInfo.packageName;
            found.put(pkg,new Choice(pkg,info.loadLabel(pm).toString()));
        }
        List<Choice> out=new ArrayList<>(found.values());out.sort((a,b)->java.text.Collator.getInstance().compare(a.label,b.label));return out;
    }
    static String canonical(String value){
        if(value==null)return "";if(value.startsWith("ComponentInfo{")&&value.endsWith("}"))value=value.substring(14,value.length()-1);
        ComponentName component=ComponentName.unflattenFromString(value);return component==null?"":component.flattenToString();
    }
    static void parse(XmlPullParser parser,Pack pack)throws Exception {
        int count=0;
        for(int event=parser.getEventType();event!=XmlPullParser.END_DOCUMENT;event=parser.next()){
            if(event!=XmlPullParser.START_TAG||!"item".equals(parser.getName()))continue;
            if(++count>100000)throw new IOException("Icon index too large");
            String icon=parser.getAttributeValue(null,"drawable"),component=canonical(parser.getAttributeValue(null,"component"));
            if(icon==null||!icon.matches("[a-zA-Z0-9_]+"))continue;
            pack.icons.add(icon);if(!component.isEmpty())pack.mappings.put(component,icon);
        }
    }
    private static void read(Pack pack,String name){
        for(String type:new String[]{"xml","raw","assets"})try{
            int id="assets".equals(type)?0:pack.resources.getIdentifier(name,type,pack.name);
            if("xml".equals(type)){
                if(id==0)continue;try(XmlResourceParser parser=pack.resources.getXml(id)){parse(parser,pack);}return;
            }
            if(!"assets".equals(type)&&id==0)continue;
            try(InputStream in="assets".equals(type)?pack.resources.getAssets().open(name+".xml"):pack.resources.openRawResource(id)){
                XmlPullParser parser=Xml.newPullParser();parser.setInput(in,"UTF-8");parse(parser,pack);
            }return;
        }catch(Exception ignored){}
    }
    static Pack pack(Context c,String pkg){
        if(pkg==null||pkg.isEmpty())return null;
        long generation;
        synchronized(IconTheme.class){
            if(cache.containsKey(pkg))return cache.get(pkg);
            generation=packGeneration;
        }
        Pack pack=null;
        try{pack=new Pack(pkg,c.getPackageManager().getResourcesForApplication(pkg));read(pack,"appfilter");read(pack,"drawable");}catch(PackageManager.NameNotFoundException|RuntimeException ignored){}
        synchronized(IconTheme.class){
            if(generation!=packGeneration)return null;
            if(cache.containsKey(pkg))return cache.get(pkg);
            if(cache.size()>=4)cache.remove(cache.keySet().iterator().next());
            cache.put(pkg,pack);return pack;
        }
    }
    static synchronized void invalidate(){packGeneration++;cache.clear();}
    static boolean changed(String key){return key!=null&&(key.equals(PACK)||key.equals(REVISION)||key.startsWith(PREFIX));}
    static String key(String component){return PREFIX+canonical(component);}
    static void selectPack(Context c,String pkg){Launches.prefs(c).edit().putString(PACK,pkg).apply();}
    static void override(Context c,String component,String value){
        SharedPreferences prefs=Launches.prefs(c);String key=key(component),old=prefs.getString(key,"");
        SharedPreferences.Editor edit=prefs.edit();if(value.isEmpty())edit.remove(key);else edit.putString(key,value);edit.apply();
        if(old.startsWith("file:")&&!old.equals(value)){File file=localFile(c,old.substring(5));if(file!=null)file.delete();}
    }
    private static File localFile(Context c,String name){return name.matches("icon-[a-zA-Z0-9-]+\\.png")?new File(c.getFilesDir(),name):null;}
    static Drawable resolve(Context c,String component,Drawable original){
        String requested=Profiles.requestedComponent(c,component);SharedPreferences prefs=Launches.prefs(c);
        String custom=prefs.getString(key(requested),"");
        if("default".equals(custom))return defaultIcon(c,component,original);
        if(custom.startsWith("file:"))try{
            File file=localFile(c,custom.substring(5));if(file!=null&&file.isFile()){
                Bitmap bitmap=BitmapFactory.decodeFile(file.getPath());if(bitmap!=null)return new BitmapDrawable(c.getResources(),bitmap);
            }
        }catch(RuntimeException ignored){}
        if(custom.startsWith("pack:")){
            String[] parts=custom.split(":",3);if(parts.length==3){Pack pack=pack(c,parts[1]);Drawable icon=pack==null?null:pack.drawable(parts[2]);if(icon!=null)return icon;}
        }
        Pack pack=pack(c,prefs.getString(PACK,""));
        if(pack!=null){String icon=pack.mappings.get(canonical(requested));if(icon==null)icon=pack.mappings.get(canonical(component));Drawable drawable=icon==null?null:pack.drawable(icon);if(drawable!=null)return drawable;}
        return defaultIcon(c,component,original);
    }
    private static Drawable defaultIcon(Context c,String component,Drawable original){
        return AppIcons.display(original==null?net.fuyumori.stellashell.feature.launch.AppCatalog.icon(c,component):original);
    }
    /** Decode to a bounded raster and keep a private copy; no broad storage permission or URI lease. */
    static File importImage(Context c,Uri uri)throws IOException {
        Bitmap bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(),uri),(decoder,info,source)->{
            int w=info.getSize().getWidth(),h=info.getSize().getHeight();float scale=Math.min(1f,256f/Math.max(w,h));
            decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        File file=File.createTempFile("icon-",".png",c.getFilesDir());
        try(OutputStream out=new FileOutputStream(file)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Image encoding failed");}
        catch(IOException e){file.delete();throw e;}finally{bitmap.recycle();}return file;
    }
    static void watchPackages(Context c){
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_PACKAGE_ADDED);filter.addAction(Intent.ACTION_PACKAGE_REMOVED);filter.addAction(Intent.ACTION_PACKAGE_CHANGED);filter.addAction(Intent.ACTION_PACKAGE_REPLACED);filter.addDataScheme("package");
        // Package broadcasts are protected system broadcasts; no externally callable command surface.
        BroadcastReceiver changed=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){
            net.fuyumori.stellashell.feature.launch.AppCatalog.invalidate();invalidate();Launches.prefs(context).edit().putLong(REVISION,System.nanoTime()).apply();
        }};
        c.registerReceiver(changed,filter);
        IntentFilter external=new IntentFilter();external.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);external.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        c.registerReceiver(changed,external);
    }
}
