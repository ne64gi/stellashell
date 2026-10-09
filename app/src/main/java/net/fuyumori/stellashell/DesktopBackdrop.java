package net.fuyumori.stellashell;

import android.app.*;
import android.appwidget.AppWidgetHost;
import android.content.*;
import android.os.*;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import java.util.*;

/** Inert desktop task plus separately owned, disposable interaction UI. No task reordering. */
final class DesktopBackdrop implements AutoCloseable {
    private static final Map<String,DesktopBackdrop> pending = new HashMap<>();
    final DesktopActivity activity;
    private final Bridge bridge;
    private final int display,task;
    private final IBinder owner=new Binder();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<Hold> holds=new LinkedHashSet<>();
    private final Map<Integer,Hold> results=new HashMap<>();
    private final Runnable connectionChanged=this::refresh;
    private final String key=UUID.randomUUID().toString();
    private DesktopInteractionActivity host;
    private Hold editing;
    private boolean closed,launching;

    DesktopBackdrop(DesktopActivity activity){
        this.activity=activity;display=activity.getDisplay().getDisplayId();task=activity.getTaskId();
        ShellPanels.track(display,this,activity.root);
        bridge=Bridge.get(activity);bridge.observe(connectionChanged);refresh();
    }
    private boolean enabled(){
        // Compact changes navigation and app placement, not the fullscreen
        // desktop's task-focus behavior. Both external presentations need this.
        return !closed&&ShellRuntime.enabled(activity)&&display>0;
    }
    void refresh(){
        if(closed||!bridge.ready())return;
        boolean protect=enabled();
        bridge.read(s->s.syncDesktopBackdrop(display,task,protect,owner),(value,error)->{if(error!=null)Log.w("StellaBackdrop",error);});
    }
    private final class Hold implements AutoCloseable {
        Runnable ready,dismiss;boolean released;
        @Override public void close(){
            if(released)return;released=true;holds.remove(this);
            results.values().removeIf(value->value==this);
            // Nested popup→dialog/result handoffs in this event keep their existing host.
            DesktopInteractionActivity current=host;
            main.post(()->{if(!closed&&holds.isEmpty()&&host==current&&current!=null)current.finishOwned();});
        }
    }
    AutoCloseable interact(Runnable ready){
        Hold hold=new Hold();
        if(closed||activity.isFinishing()||activity.isDestroyed()){hold.released=true;return hold;}
        if(!enabled()){ready.run();hold.released=true;return hold;}
        hold.ready=ready;holds.add(hold);
        if(host!=null&&!host.isFinishing()){host.getWindow().getDecorView().post(()->showReady(hold));}
        else if(!launching){
            launching=true;pending.put(key,this);
            try{
                Bundle options=ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle();
                options.putInt("android.activity.windowingMode",1);
                activity.startActivity(new Intent(activity,DesktopInteractionActivity.class).putExtra("desktop_owner",key)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK),options);
            }catch(RuntimeException error){failInteractions(error);}
        }
        return hold;
    }
    private void showReady(Hold hold){
        if(closed||hold.released||host==null||host.isFinishing())return;
        Runnable ready=hold.ready;hold.ready=null;
        if(ready!=null)try{ready.run();}catch(RuntimeException error){hold.close();showFailure(error);}
    }
    static DesktopBackdrop claim(String key,DesktopInteractionActivity host){
        DesktopBackdrop backdrop=pending.remove(key);
        if(backdrop==null)return null;
        if(backdrop.closed||backdrop.activity.isFinishing()||backdrop.activity.isDestroyed()
                ||host.getDisplay()==null||host.getDisplay().getDisplayId()!=backdrop.display
                ||host.getTaskId()==backdrop.task){
            backdrop.failInteractions(new IllegalStateException("Desktop editor is no longer available"));return null;
        }
        backdrop.launching=false;backdrop.host=host;
        ShellPanels.activate(backdrop.display,backdrop,backdrop::dismissInteractions);
        ShellPanels.bounds(backdrop.display,backdrop,new android.graphics.Rect(0,0,
                backdrop.activity.root.getWidth(),backdrop.activity.root.getHeight()));
        ViewGroup old=(ViewGroup)backdrop.activity.root.getParent();if(old!=null)old.removeView(backdrop.activity.root);
        host.setContentView(backdrop.activity.root);
        host.getWindow().getDecorView().post(()->{
            if(backdrop.holds.isEmpty())host.finishOwned();
            else for(Hold hold:new ArrayList<>(backdrop.holds))backdrop.showReady(hold);
        });
        return backdrop;
    }
    void hostDestroyed(DesktopInteractionActivity previous){
        if(host!=previous)return;host=null;
        ShellPanels.release(display,this);
        ViewGroup parent=(ViewGroup)activity.root.getParent();if(parent!=null)parent.removeView(activity.root);
        if(!closed&&!activity.isDestroyed()&&!activity.isFinishing()){
            activity.setContentView(activity.root);activity.root.requestApplyInsets();
            if(!holds.isEmpty()&&!launching)failInteractions(new IllegalStateException("Desktop editor closed"));
        }
    }
    private void failInteractions(RuntimeException error){
        launching=false;pending.remove(key);
        for(Hold hold:new ArrayList<>(holds)){if(hold.dismiss!=null)hold.dismiss.run();hold.close();}
        results.clear();editing=null;
        if(!closed)showFailure(error);
    }
    private void dismissInteractions(){
        for(Integer request:new ArrayList<>(results.keySet()))result(request,Activity.RESULT_CANCELED,null);
        activity.finishDesktopInteraction();
        for(Hold hold:new ArrayList<>(holds)){if(hold.dismiss!=null)hold.dismiss.run();hold.close();}
        editing=null;
    }
    private void showFailure(RuntimeException error){
        Log.w("StellaBackdrop","Desktop interaction failed",error);
        Launches.problem(activity,activity.getString(R.string.ui_operation_failed));
    }
    void editing(boolean active){
        if(closed||active==(editing!=null))return;
        if(active)editing=(Hold)interact(()->{});else{editing.close();editing=null;}
    }
    void showPopup(PopupMenu popup){
        Hold[] scope={null};
        scope[0]=(Hold)interact(()->popup.show());
        scope[0].dismiss=popup::dismiss;
        popup.setOnDismissListener(ignored->scope[0].close());
    }
    static void showPopup(Context context,PopupMenu popup){
        DesktopBackdrop backdrop=of(context);
        if(backdrop!=null&&backdrop.enabled())backdrop.showPopup(popup);else popup.show();
    }
    private static DesktopBackdrop of(Context context){return context instanceof DesktopActivity?((DesktopActivity)context).backdrop:null;}
    static void showDialog(Activity activity,Dialog dialog){
        DesktopBackdrop backdrop=of(activity);
        if(backdrop==null||!backdrop.enabled()){dialog.show();return;}
        Hold[] scope={null};
        scope[0]=(Hold)backdrop.interact(()->{
            DesktopInteractionActivity host=backdrop.host;
            dialog.getWindow().setContainer(host.getWindow());
            dialog.getWindow().setWindowManager(host.getWindowManager(),host.getWindow().getDecorView().getWindowToken(),host.getComponentName().flattenToString());
            dialog.show();
        });
        scope[0].dismiss=dialog::dismiss;
        dialog.setOnDismissListener(ignored->scope[0].close());
    }
    static void startActivityForResult(Activity activity,Intent intent,int request){
        DesktopBackdrop backdrop=of(activity);
        if(backdrop==null||!backdrop.enabled()){activity.startActivityForResult(intent,request);return;}
        backdrop.resultFlow(request,()->backdrop.host.startActivityForResult(intent,request));
    }
    static void configureWidget(Activity activity,AppWidgetHost widgets,int id,int request,Bundle options){
        DesktopBackdrop backdrop=of(activity);
        if(backdrop==null||!backdrop.enabled()){widgets.startAppWidgetConfigureActivityForResult(activity,id,0,request,options);return;}
        backdrop.resultFlow(request,()->widgets.startAppWidgetConfigureActivityForResult(backdrop.host,id,0,request,options));
    }
    private void resultFlow(int request,Runnable launch){
        if(results.containsKey(request))throw new IllegalStateException("Desktop action already open");
        Hold hold=(Hold)interact(launch);
        // Launch failure / activity shutdown may synchronously release the hold.
        if(!hold.released)results.put(request,hold);
    }
    void result(int request,int result,Intent data){
        Hold hold=results.remove(request);
        if(hold==null||closed)return;
        try{activity.onActivityResult(request,result,data);}finally{hold.close();}
    }
    @Override public void close(){
        if(closed)return;closed=true;bridge.remove(connectionChanged);pending.remove(key);
        ShellPanels.release(display,this);
        for(Hold hold:new ArrayList<>(holds)){if(hold.dismiss!=null)hold.dismiss.run();hold.close();}
        results.clear();editing=null;
        if(host!=null)host.finishOwned();
        if(bridge.ready())bridge.read(s->s.syncDesktopBackdrop(display,task,false,owner),(value,error)->{if(error!=null)Log.w("StellaBackdrop",error);});
    }
}
