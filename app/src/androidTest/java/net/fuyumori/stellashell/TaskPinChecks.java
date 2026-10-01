package net.fuyumori.stellashell;

import android.app.TaskInfo;
import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Binder;
import java.util.*;

/** Ownership/lifecycle regressions, independent of the device's native pin capability. */
public final class TaskPinChecks {
    public static void main(String[] args)throws Exception {run();System.out.println("PASS: window pin lifecycle/identity/cleanup checks");System.exit(0);}
    private static final class Window {
        final int id;final Object token=new Binder();boolean top,visible=true,focused=true;int mode=5;
        Window(int id){this.id=id;}
        FrameworkTaskAccess.Entry entry(){TaskInfo info=new android.app.ActivityManager.RunningTaskInfo();info.taskId=id;info.baseActivity=new ComponentName("fixture.app","fixture.app.Window"+id);return new FrameworkTaskAccess.Entry(info,0,0,mode,1,visible,focused,top,token,new Rect(0,0,400,400));}
    }
    private static final class Store implements TaskPins.Access {
        final Map<Integer,Window> live=new HashMap<>();int writes;int failId=-1;boolean failAfterWrite;
        public List<FrameworkTaskAccess.Entry> all(){List<FrameworkTaskAccess.Entry> out=new ArrayList<>();for(Window window:live.values())out.add(window.entry());return out;}
        public void set(FrameworkTaskAccess.Entry task,boolean enabled)throws Exception {
            Window window=live.get(task.id);if(window==null||window.token!=task.token)throw new IllegalStateException("stale task");
            if(task.id==failId&&!failAfterWrite)throw new IllegalStateException("fixture failure");
            window.top=enabled;writes++;
            if(task.id==failId&&failAfterWrite)throw new IllegalStateException("fixture post-dispatch failure");
        }
    }
    static void run()throws Exception {
        Store store=new Store();TaskPins pins=new TaskPins(store);Window one=new Window(1),two=new Window(2);store.live.put(1,one);store.live.put(2,two);
        pins.set(one.entry(),true);require(one.top&&pins.pinned(one.entry()),"pin applied");
        pins.suspend(one.entry());one.visible=false;one.focused=false;
        require(!one.top&&pins.pinned(one.entry()),"minimize retains intent without topmost flag");
        int writes=store.writes;pins.reconcile();require(store.writes==writes,"minimized pin must not pop back up");
        one.visible=true;one.focused=true;pins.reconcile();require(one.top,"external focus resumes suspended pin");
        pins.suspend(one.entry());pins.resume(one.entry());require(one.top,"explicit restore resumes pin");
        pins.set(two.entry(),true);pins.set(one.entry(),false);require(!one.top&&two.top,"unpin affects only chosen task");
        pins.fullscreen(two.entry());two.mode=1;require(!two.top&&!pins.pinned(two.entry()),"fullscreen clears pin");
        two.mode=5;pins.set(two.entry(),true);two.mode=1;pins.reconcile();require(!two.top,"external mode change clears latent pin");
        pins.set(one.entry(),true);one.top=false;writes=store.writes;pins.reconcile();require(store.writes==writes&&!pins.pinned(one.entry()),"external unpin is respected");
        pins.set(one.entry(),true);Window reused=new Window(1);store.live.put(1,reused);writes=store.writes;pins.release();require(store.writes==writes&&!reused.top,"task ID reuse must not touch new task");
        two.mode=5;pins.set(two.entry(),true);store.live.remove(2);pins.reconcile();pins.release();
        store.live.put(2,two);pins.set(reused.entry(),true);pins.set(two.entry(),true);store.failId=1;
        boolean failed=false;try{pins.release();}catch(Exception expected){failed=true;}require(failed&&reused.top&&!two.top,"cleanup continues past one failure");
        store.failId=-1;pins.release();require(!reused.top,"failed cleanup can retry");
        store.failId=1;store.failAfterWrite=true;failed=false;try{pins.set(reused.entry(),true);}catch(Exception expected){failed=true;}require(failed,"post-dispatch failure surfaced");
        store.failId=-1;pins.release();require(!reused.top,"failed pin dispatch remains recoverable");
        Window pip=new Window(3);pip.mode=2;pip.top=true;store.live.put(3,pip);require(!pins.pinned(pip.entry()),"PiP is not a user pin");
        TaskSession.Task picture=view(10,2,false),pinned=view(11,5,true),ordinary=view(12,5,false);
        List<TaskSession.Task> order=Arrays.asList(picture,pinned,ordinary);
        List<TaskSession.Task> dragged=WindowChrome.dragOrder(order,ordinary);
        require(dragged.get(0).id==10&&dragged.get(1).id==11&&dragged.get(2).id==12,"drag chrome must stay below pinned window and PiP");
        dragged=WindowChrome.dragOrder(order,pinned);require(dragged.get(0).id==10&&dragged.get(1).id==11,"pinned drag must stay below PiP");
    }
    private static TaskSession.Task view(int id,int mode,boolean pinned)throws Exception {
        return new TaskSession.Task(new org.json.JSONObject().put("id",id).put("mode",mode).put("alwaysOnTop",pinned).put("component","fixture.app/fixture.app.Window"+id).put("visible",true).put("focused",false).put("left",0).put("top",0).put("right",300).put("bottom",300));
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
