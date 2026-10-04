package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.display.ScreenScalePolicy;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

/** Shizuku-only display-density access. No arbitrary command or primary-mode fallback. */
final class ScreenScaling {
    interface Commands {String run(String... args)throws Exception;}
    private static final class Snapshot {
        final String token=UUID.randomUUID().toString();final ScreenScalePolicy.State state;
        Snapshot(ScreenScalePolicy.State state){this.state=state;}
    }
    private final DisplayManager displays;private final Commands commands;
    private final Map<Integer,Snapshot> snapshots=new HashMap<>();
    private Object windowManager;private Class<?> windowManagerApi;
    ScreenScaling(Context context,Commands commands){
        if(context==null)throw new IllegalStateException("Shizuku context unavailable");
        displays=context.getSystemService(DisplayManager.class);this.commands=commands;
    }
    private Display display(int id){
        if(id<0)throw new IllegalArgumentException("Invalid screen");Display display=displays.getDisplay(id);
        if(display==null||!display.isValid()||(display.getFlags()&Display.FLAG_PRIVATE)!=0)throw new IllegalStateException("Screen changed or disconnected");
        return display;
    }
    private static String identity(Display display)throws Exception{
        String value=(String)Display.class.getMethod("getUniqueId").invoke(display);
        if(value==null||value.isEmpty())throw new IllegalStateException("Screen identity unavailable");return value;
    }
    private int density(String method,int id)throws Exception{
        if(windowManager==null){windowManagerApi=Class.forName("android.view.IWindowManager");windowManager=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);}
        return (Integer)windowManagerApi.getMethod(method,int.class).invoke(windowManager,id);
    }
    private ScreenScalePolicy.State read(int id)throws Exception{
        String expected=identity(display(id));
        // Android 14's `wm density -d ID` query can report display 0. Read the
        // initial/base density with explicit Binder arguments instead. Writes
        // remain bounded wm density VALUE/reset -d ID commands on every OS.
        int physical=density("getInitialDisplayDensity",id),current=density("getBaseDisplayDensity",id);
        if(!expected.equals(identity(display(id))))throw new IllegalStateException("Screen changed or disconnected");
        // This describes effective density, like wm's physical/override output.
        // An equal-to-physical persisted override is not observable here; do
        // not claim that its underlying preference is absent.
        return new ScreenScalePolicy.State(expected,new ScreenScalePolicy.Density(physical,current==physical?0:current));
    }
    private String remember(int id,ScreenScalePolicy.State state)throws Exception{
        Display display=display(id);if(!state.identity.equals(identity(display)))throw new IllegalStateException("Screen changed or disconnected");
        Point size=new Point();display.getRealSize(size);Snapshot snapshot=snapshots.get(id);
        if(snapshot==null||!snapshot.state.identity.equals(state.identity)||!snapshot.state.density.equals(state.density))snapshot=new Snapshot(state);
        snapshots.put(id,snapshot);
        return new JSONObject().put("identity",snapshot.token).put("screenIdentity",state.identity).put("physicalDensity",state.density.physical).put("density",state.density.current())
                .put("overrideDensity",state.density.override).put("standard",state.density.override==0).put("percent",state.density.percent())
                .put("width",size.x).put("height",size.y).toString();
    }
    String snapshot(int id)throws Exception{return remember(id,read(id));}
    String apply(int id,String token,int percent)throws Exception{
        Snapshot before=snapshots.get(id);
        if(before==null||token==null||!before.token.equals(token))throw new IllegalStateException("Screen snapshot changed; refresh before applying");
        try{
            ScreenScalePolicy.State after=ScreenScalePolicy.apply(new ScreenScalePolicy.Store(){
                public ScreenScalePolicy.State read(int target)throws Exception{return ScreenScaling.this.read(target);}
                public void write(int target,int override)throws Exception{
                    if(!before.state.identity.equals(identity(display(target))))throw new IllegalStateException("Screen changed or disconnected");
                    commands.run(ScreenScalePolicy.command(target,override));
                }
            },id,before.state,percent);
            return remember(id,after);
        }catch(Exception failure){snapshots.remove(id);throw failure;}
    }
}
