package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.launch.AppLaunchProfile;
import net.fuyumori.stellashell.core.launch.LaunchProfileSnapshot;
import org.json.JSONException;
import org.json.JSONObject;

/** Existing launch_profiles JSON version 1 contract, independent of UI and task sessions. */
final class LaunchProfileCodec {
    private LaunchProfileCodec() {}
    static LaunchProfileSnapshot decode(String component,String value,boolean freeformDefault) {
        AppLaunchProfile p=new AppLaunchProfile(component);
        p.launchMode=freeformDefault?AppLaunchProfile.Mode.WINDOWED:AppLaunchProfile.Mode.FULLSCREEN;
        try{
            JSONObject j=new JSONObject(value);
            p.launchMode=AppLaunchProfile.Mode.valueOf(j.optString("launchMode",p.launchMode.name()));
            p.lastState=AppLaunchProfile.Mode.valueOf(j.optString("lastState","WINDOWED"));
            p.size=AppLaunchProfile.Size.valueOf(j.optString("size","SMALL"));p.position=AppLaunchProfile.Position.valueOf(j.optString("position","AUTO"));
            p.width=j.optInt("width",800);p.height=j.optInt("height",600);p.x=j.optInt("x");p.y=j.optInt("y");p.lastWidth=j.optInt("lastWidth");p.lastHeight=j.optInt("lastHeight");
            p.hasLastBounds=j.optBoolean("hasLastBounds")&&p.lastWidth>0&&p.lastHeight>0;p.rememberBounds=j.optBoolean("rememberBounds",true);
            p.resolvedComponent=j.optString("resolvedComponent","");
        }catch(Exception ignored){}
        return new LaunchProfileSnapshot(p);
    }
    static String encode(LaunchProfileSnapshot p) {
        try{
            return new JSONObject().put("version",1).put("packageName",p.packageName).put("activityName",p.activityName)
                    .put("launchMode",p.launchMode.name()).put("size",p.size.name()).put("position",p.position.name())
                    .put("width",p.width).put("height",p.height).put("x",p.x).put("y",p.y).put("lastWidth",p.lastWidth).put("lastHeight",p.lastHeight)
                    .put("hasLastBounds",p.hasLastBounds).put("rememberBounds",p.rememberBounds).put("lastState",p.lastState.name())
                    .put("preferredDisplay","AUTO").put("resolvedComponent",p.resolvedComponent).toString();
        }catch(JSONException error){throw new IllegalStateException(error);}
    }
}
