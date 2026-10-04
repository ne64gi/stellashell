package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.widget.LinearLayout;
import android.view.View;
import java.lang.reflect.Field;

/** Read-only production feed. Forgets only this client's cached service, never stops Shizuku. */
final class ActiveFeedRecoveryChecks {
    private final Instrumentation test;private final Context context;
    ActiveFeedRecoveryChecks(Instrumentation test){this.test=test;context=test.getTargetContext();}
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private static Field field(Class<?> type,String name)throws ReflectiveOperationException{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private void main(Runnable action){Throwable[] error={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    private void await(java.util.function.BooleanSupplier condition,String message)throws Exception{
        long until=SystemClock.uptimeMillis()+15000;
        while(SystemClock.uptimeMillis()<until){boolean[] ok={false};main(()->ok[0]=condition.getAsBoolean());if(ok[0])return;Thread.sleep(100);}
        throw new AssertionError(message);
    }
    void run()throws Exception{
        check(Launches.prefs(context).getBoolean("enabled",false)&&Launches.prefs(context).getBoolean("primary_mode",false),"Requires an already enabled phone session");
        Bridge bridge=Bridge.get(context);main(()->{check(bridge.authorized(),"Shizuku must already be running and authorized");ShellRuntime.start(context,0,false);bridge.connect();});
        await(bridge::ready,"Initial Shizuku connection unavailable");await(ShellRuntime::phoneNavigationReady,"Phone sidebar unavailable");test.waitForIdleSync();
        Field serviceField=field(Bridge.class,"service");IDesktopBridge previous=(IDesktopBridge)serviceField.get(bridge);
        PhoneRunningTasks[] feed={null};PhoneSidebar[] sidebar={null};
        try{
            main(()->{
                try{
                    sidebar[0]=ShellFixtureAccess.sidebar();
                    check(sidebar[0]!=null,"Phone Sidebar is not owned by the current navigation runtime");
                    sidebar[0].show(true);
                    feed[0]=(PhoneRunningTasks)field(PhoneSidebar.class,"running").get(sidebar[0]);
                    serviceField.set(bridge,null);
                    check(!bridge.ready(),"Client cache did not disconnect");feed[0].refresh();
                    check(feed[0].state()==PhoneRunningTasks.State.UNAVAILABLE,"Disconnected feed state lost");
                    LinearLayout section=(LinearLayout)field(PhoneSidebar.class,"activeSection").get(sidebar[0]);
                    LinearLayout rows=(LinearLayout)field(PhoneSidebar.class,"activeTasks").get(sidebar[0]);
                    check(section.getVisibility()==View.GONE&&rows.getChildCount()==0,"Unavailable section retained separator, text or stale icons");
                }catch(ReflectiveOperationException e){throw new AssertionError(e);}
            });
            await(()->bridge.ready()&&feed[0].state()==PhoneRunningTasks.State.READY,"Visible sidebar did not reconnect while its active section was hidden");
            try{await(()->{
                try{
                    PhoneRunningTasks running=(PhoneRunningTasks)field(PhoneSidebar.class,"running").get(sidebar[0]);
                    LinearLayout rows=(LinearLayout)field(PhoneSidebar.class,"activeTasks").get(sidebar[0]);
                    Context phoneContext=(Context)field(PhoneSidebar.class,"context").get(sidebar[0]);
                    java.util.List<TaskSnapshot.Task> tasks=running.tasks();
                    int expected=PhoneSidebar.unpinnedTasks(tasks,Launches.pins(phoneContext)).size();
                    LinearLayout section=(LinearLayout)field(PhoneSidebar.class,"activeSection").get(sidebar[0]);
                    return running.state()==PhoneRunningTasks.State.READY&&rows.getChildCount()==expected&&section.getVisibility()==(expected==0?View.GONE:View.VISIBLE);
                }catch(ReflectiveOperationException e){throw new AssertionError(e);}
            },"Production sidebar did not render the active feed or true empty state");}
            catch(AssertionError failure){
                String[] diagnostic={"unavailable"};
                main(()->{
                    try{
                        PhoneRunningTasks running=(PhoneRunningTasks)field(PhoneSidebar.class,"running").get(sidebar[0]);
                        LinearLayout rows=(LinearLayout)field(PhoneSidebar.class,"activeTasks").get(sidebar[0]);
                        diagnostic[0]="shown="+field(PhoneSidebar.class,"shown").getBoolean(sidebar[0])
                                +", closed="+field(PhoneSidebar.class,"closed").getBoolean(sidebar[0])
                                +", current="+(ShellFixtureAccess.sidebar()==sidebar[0])
                                +", state="+running.state()+", tasks="+running.tasks().size()
                                +", rows="+(rows==null?-1:rows.getChildCount())
                                +", bridgeReady="+bridge.ready()+", authorized="+bridge.authorized();
                    }catch(ReflectiveOperationException error){throw new AssertionError(error);}
                });
                throw new AssertionError(failure.getMessage()+" ["+diagnostic[0]+"]",failure);
            }
        }finally{
            main(()->{
                if(sidebar[0]!=null)sidebar[0].hide();
                if(!bridge.ready()&&previous.asBinder().isBinderAlive())try{serviceField.set(bridge,previous);}catch(ReflectiveOperationException e){throw new AssertionError(e);}
            });
        }
    }
}
