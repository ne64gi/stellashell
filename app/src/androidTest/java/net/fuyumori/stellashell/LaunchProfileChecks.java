package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fuyumori.stellashell.core.launch.AppLaunchProfile;
import net.fuyumori.stellashell.core.launch.LaunchProfileOwner;
import net.fuyumori.stellashell.core.launch.LaunchProfileSnapshot;
import org.json.JSONObject;

/** Version 1 persistence regression in nonce preferences only; no launches or personal task changes. */
final class LaunchProfileChecks {
    static void run(Instrumentation test)throws Exception {
        Context actual=test.getTargetContext();String file="launch_profile_fixture_"+UUID.randomUUID();
        SharedPreferences preferences=actual.getSharedPreferences(file,Context.MODE_PRIVATE);
        String requested="net.fuyumori.stellashell.test/.ProfileAlias",key=Profiles.key(requested);
        String resolved="net.fuyumori.stellashell.test/net.fuyumori.stellashell.test.Resolved";
        boolean[] freeform={false};
        LaunchProfilePreferencesStore store=new LaunchProfilePreferencesStore(preferences,()->freeform[0]);
        LaunchProfilePreferencesStore secondStore=new LaunchProfilePreferencesStore(preferences,()->freeform[0]);
        LaunchProfileOwner owner=new LaunchProfileOwner(store);
        Throwable[] failure={null};
        try{
            test.runOnMainSync(()->{
                try{
                    check(key.equals("net.fuyumori.stellashell.test/net.fuyumori.stellashell.test.ProfileAlias"),"Legacy component key changed");
                    check(owner.snapshot(key).launchMode==AppLaunchProfile.Mode.FULLSCREEN,"Missing profile must use freeform=false default");
                    freeform[0]=true;check(owner.snapshot(key).launchMode==AppLaunchProfile.Mode.WINDOWED,"Missing profile must use current freeform default");
                    check(preferences.getAll().isEmpty(),"Read must not persist defaults");
                    JSONObject legacy=new JSONObject().put("version",1).put("packageName","ignored.identity").put("activityName","Ignored")
                            .put("launchMode","RESTORE_LAST").put("lastState","MAXIMIZED").put("size","CUSTOM").put("position","LAST")
                            .put("width",1100).put("height",750).put("x",321).put("y",234).put("lastWidth",901).put("lastHeight",601)
                            .put("hasLastBounds",true).put("rememberBounds",true).put("preferredDisplay","old-output").put("resolvedComponent",resolved);
                    preferences.edit().putString(key,legacy.toString()).commit();
                    Map<String,?> before=preferences.getAll();LaunchProfileSnapshot popup=owner.snapshot(key);
                    check(preferences.getAll().equals(before),"Legacy decode must not rewrite JSON");
                    check(popup.launchMode==AppLaunchProfile.Mode.RESTORE_LAST&&popup.lastState==AppLaunchProfile.Mode.MAXIMIZED,"Legacy modes changed");
                    check(popup.size==AppLaunchProfile.Size.CUSTOM&&popup.position==AppLaunchProfile.Position.LAST,"Legacy placement choices changed");
                    check(popup.width==1100&&popup.height==750&&popup.x==321&&popup.y==234&&popup.lastWidth==901&&popup.lastHeight==601&&popup.hasLastBounds,"Absolute persisted geometry changed");
                    check(popup.packageName.equals("net.fuyumori.stellashell.test")&&popup.activityName.endsWith(".ProfileAlias"),"Key must own profile identity");
                    check(popup.preferredDisplay.equals("AUTO")&&popup.resolvedComponent.equals(resolved),"Alias/AUTO contract changed");
                    check(store.lockIdentity()==secondStore.lockIdentity(),"Adapters must share their backing preferences lock");
                    Set<String> stored=store.storedComponents();
                    try{stored.remove(key);throw new AssertionError("Stored component window was mutable");}catch(UnsupportedOperationException expected){}
                    check(owner.requestedComponent(resolved).equals(key),"Stored resolved alias lookup changed");
                    new LaunchProfileOwner(secondStore).setMode(resolved,AppLaunchProfile.Mode.FULLSCREEN);
                    check(owner.requestedComponent(resolved).equals(resolved),"Direct requested profile must win over its alias");
                    check(owner.observedComponent(resolved,resolved).equals(key),"Observation must prefer alias even if resolved profile exists");
                    check(!stored.contains(resolved),"Stored component window was not detached");
                    owner.observe(key,AppLaunchProfile.Mode.WINDOWED,451,312,930,680);
                    owner.observe(key,AppLaunchProfile.Mode.MAXIMIZED,0,0,1920,1080);
                    owner.setMode(key,AppLaunchProfile.Mode.WINDOWED);owner.setCustomSize(key,1200,800);
                    LaunchProfileSnapshot saved=owner.snapshot(key);
                    check(saved.lastState==AppLaunchProfile.Mode.MAXIMIZED&&saved.resolvedComponent.equals(resolved),"Stale UI save replaced observed state or alias");
                    check(saved.x==451&&saved.y==312&&saved.lastWidth==930&&saved.lastHeight==680,"Stale UI save replaced observed bounds");
                    check(popup.x==321&&popup.width==1100,"Old popup snapshot mutated");
                    AppLaunchProfile detached=saved.toMutable();detached.x=17;detached.width=240;
                    check(owner.snapshot(key).x==451&&owner.snapshot(key).width==1200,"Mutable planner escaped into store");
                    JSONObject encoded=new JSONObject(preferences.getString(key,""));
                    check(encoded.getInt("version")==1&&encoded.getString("preferredDisplay").equals("AUTO"),"Legacy serialization header changed");
                    HashSet<String> fields=new HashSet<>();java.util.Iterator<String> keys=encoded.keys();while(keys.hasNext())fields.add(keys.next());
                    check(fields.equals(new HashSet<>(Arrays.asList("version","packageName","activityName","launchMode","size","position","width","height","x","y","lastWidth","lastHeight","hasLastBounds","rememberBounds","lastState","preferredDisplay","resolvedComponent"))),"Version 1 fields changed");
                    check(encoded.getInt("x")==451&&encoded.getInt("y")==312,"Codec converted absolute coordinates to local coordinates");
                    before=preferences.getAll();try{owner.setCustomSize(key,239,600);throw new AssertionError("Invalid dimensions accepted");}catch(IllegalArgumentException expected){}
                    check(preferences.getAll().equals(before),"Invalid edit persisted changes");
                    preferences.edit().putString(key,"{malformed").commit();
                    check(owner.snapshot(key).launchMode==AppLaunchProfile.Mode.WINDOWED&&owner.snapshot(key).width==800,"Malformed legacy JSON defaults changed");
                    preferences.edit().putString(key,"{\"launchMode\":\"FULLSCREEN\",\"lastState\":\"invalid\",\"width\":999}").commit();
                    check(owner.snapshot(key).launchMode==AppLaunchProfile.Mode.FULLSCREEN&&owner.snapshot(key).width==800,"Legacy partial decode behavior changed");
                    preferences.edit().putString(key,"{\"hasLastBounds\":true,\"lastWidth\":0,\"lastHeight\":200}").commit();
                    check(!owner.snapshot(key).hasLastBounds,"Invalid saved geometry accepted");
                }catch(Throwable error){failure[0]=error;}
            });
            if(failure[0]!=null)throw new AssertionError(failure[0]);
        }finally{test.runOnMainSync(()->actual.deleteSharedPreferences(file));}
    }
    private static void check(boolean ok,String note){if(!ok)throw new AssertionError(note);}
}
