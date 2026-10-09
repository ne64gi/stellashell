package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.*;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.Field;
import java.util.*;

/** Real taskbar Window, isolated task snapshots/commands; never operates personal app tasks. */
final class TaskbarWindowChecks {
    private final Instrumentation test;
    private final Context actual;
    private final String prefix="taskbar_window_"+UUID.randomUUID()+"_";
    private final Set<String> preferenceFiles=new HashSet<>();
    private SelectedOutputSurface surface;
    private TaskState tasks;
    private ShellSettings settings;
    private FixtureContext context;
    private VirtualDisplay display;
    private ImageReader reader;
    private TaskSession session;
    private int id=-1,lastCommand=-1;
    private String component;
    private long generation;
    private TaskbarWindowChecks(Instrumentation test){this.test=test;actual=test.getTargetContext();}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private void main(Runnable action){Throwable[] failure={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new AssertionError(failure[0]);}
    private static Field field(Class<?> c,String name){try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){throw new AssertionError(e);}}
    private Object get(String name){try{return field(SelectedOutputSurface.class,name).get(surface);}catch(Exception e){throw new AssertionError(e);}}
    private void invoke(String name){try{java.lang.reflect.Method m=SelectedOutputSurface.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(surface);}catch(Exception e){throw new AssertionError(e);}}
    private void settle() throws Exception {Thread.sleep(220);test.waitForIdleSync();}
    static void run(Instrumentation test,int expected,boolean restoreAbsentExternal) throws Exception {new TaskbarWindowChecks(test).run(expected,restoreAbsentExternal);}
    private void run(int expected,boolean restoreAbsentExternal) throws Exception {
        check(expected>0&&Launches.prefs(actual).getBoolean("enabled",false),"Requires the existing enabled external session");
        check(Displays.ids(actual).contains(expected),"Original physical external is disconnected");
        main(()->{actual.startForegroundService(new Intent(actual,DockService.class).putExtra("show_home",false));Bridge.get(actual).connect();});
        if(restoreAbsentExternal){
            // Host proved these three original keys were absent/absent/false. Restore only
            // the known HOME-bootstrap result, with a live service preventing another race.
            long runningUntil=SystemClock.uptimeMillis()+15000;
            while(SystemClock.uptimeMillis()<runningUntil&&!ShellRuntime.running())Thread.sleep(100);
            main(()->{
                SharedPreferences p=Launches.prefs(actual);
                boolean bootstrap=p.getBoolean("primary_mode",false)&&p.getInt("preferred_display",-1)==0&&p.getInt("workspace_display",-1)==0;
                boolean original=!p.getBoolean("primary_mode",true)&&!p.contains("preferred_display")&&!p.contains("workspace_display");
                check(ShellRuntime.running()&&(bootstrap||original),"Unexpected selection; refuse restoration");
                if(bootstrap)check(p.edit().remove("preferred_display").remove("workspace_display").putBoolean("primary_mode",false).commit(),"Original external selection restore failed");
            });
        }
        long until=SystemClock.uptimeMillis()+15000;
        while(SystemClock.uptimeMillis()<until&&(!ShellRuntime.running()||ShellRuntime.selectedDisplay()!=expected||!Bridge.get(actual).ready()))Thread.sleep(100);
        check(ShellRuntime.running()&&ShellRuntime.selectedDisplay()==expected&&Bridge.get(actual).ready(),"Existing external session did not resume; do not select another output");
        SharedPreferences production=Launches.prefs(actual);
        boolean autoPresent=production.contains("workspace_auto"),auto=production.getBoolean("workspace_auto",false);
        long liveGeneration=ShellRuntime.snapshot().generation;
        try{
            main(()->{
                production.edit().putBoolean("workspace_auto",false).commit();
                reader=ImageReader.newInstance(600,400,PixelFormat.RGBA_8888,2);
                reader.setOnImageAvailableListener(r->{try(Image ignored=r.acquireLatestImage()){}catch(IllegalStateException closed){}},new Handler(Looper.getMainLooper()));
                display=actual.getSystemService(DisplayManager.class).createVirtualDisplay("StellaShell taskbar Window fixture",600,400,160,reader.getSurface(),DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
                check(display!=null,"Owned display unavailable");id=display.getDisplay().getDisplayId();
                context=new FixtureContext(actual);
                String pin=new ComponentName(actual,SetupActivity.class).flattenToString();
                component=test.getContext().getPackageName()+"/net.fuyumori.stellashell.LauncherEntryFirst";
                Launches.prefs(context).edit().putBoolean("phone_profile_initialized",true).putBoolean("primary_mode",false).putBoolean("enabled",false)
                    .putBoolean("phone_sidebar",false).putBoolean("phone_taskbar",false).putBoolean("desktop_dock",false).putString("shell_layout","desktop")
                    .putInt("desktop_taskbar_scale",150).putString("pinned",String.join("\n",Collections.nCopies(6,pin))).commit();
                tasks=context.taskState();
                tasks.openOutput(context,id,()->{},()->false,new TaskSession.Backend(){
                    public boolean ready(){return false;}
                    public void readSnapshot(int display,Bridge.Reply reply){throw new AssertionError("No native task reads");}
                    public void taskOperation(int display,int task,String component,String action,Rect bounds,boolean sync,Bridge.Reply reply){check(display==id&&"focus".equals(action),"Only captured fixture focus allowed");lastCommand=task;}
                });
                session=ShellFixtureAccess.taskSession(tasks);
                surface=new SelectedOutputSurface(context,id,null,false);
            });
            settle();
            main(this::identityAndState);
            settle();
            main(()->{HorizontalScrollView bar=(HorizontalScrollView)get("dock");check(bar.getScrollX()>0,"Task update reset horizontal scroll");});
            inputAndPanels();
            main(()->{
                View old=(View)get("dock");
                settings.setExternalTaskbarScalePercent(100);surface.settingsChanged(EnumSet.of(ShellSettings.Change.EXTERNAL_TASKBAR_SCALE));
                check(get("dock")!=old&&!old.isAttachedToWindow(),"Explicit scale change must still rebuild/detach");
                fieldSet("collapsed",true);surface.rebuild();View collapsed=(View)get("dock");
                check(get("taskbarEntries")==null,"Collapsed bar has no task list");publish(task(410,true,true,false,5));
                check(get("dock")==collapsed,"Collapsed task update replaced Window");
                fieldSet("collapsed",false);surface.rebuild();publish(task(411,true,true,false,5));
                LinearLayout entries=(LinearLayout)get("taskbarEntries");entries.getChildAt(entries.getChildCount()-1).performClick();
                check(lastCommand==411,"Fresh row clicked a stale task identity");
                motion(MotionEvent.ACTION_DOWN);publish(task(412,true,true,false,5));check((Boolean)get("taskbarRefreshPending"),"Input must defer refresh");
                motion(MotionEvent.ACTION_CANCEL);View retired=(View)get("dock");surface.close();
                check(!retired.isAttachedToWindow()&&get("taskbarEntries")==null,"Close retained Window/entries");
            });
            settle();main(()->{invoke("tasksChanged");check(get("dock")==null&&!(Boolean)get("taskbarRefreshPending"),"Late refresh resurrected closed output");});
            check(ShellRuntime.running()&&ShellRuntime.selectedDisplay()==expected&&ShellRuntime.snapshot().generation==liveGeneration,"Fixture changed live output/lifecycle");
        }finally{
            main(()->{
                try{if(surface!=null)surface.close();if(tasks!=null)tasks.closeOwner();if(settings!=null)settings.close();}
                finally{if(display!=null)display.release();if(reader!=null)reader.close();if(id>0)WorkArea.remove(id);
                    SharedPreferences.Editor restore=production.edit();if(autoPresent)restore.putBoolean("workspace_auto",auto);else restore.remove("workspace_auto");restore.commit();
                    for(String name:preferenceFiles)actual.deleteSharedPreferences(name);}
            });
        }
    }
    private void fieldSet(String name,Object value){try{field(SelectedOutputSurface.class,name).set(surface,value);}catch(Exception e){throw new AssertionError(e);}}
    private TaskSnapshot.Task task(int task,boolean visible,boolean focused,boolean pinned,int mode){return new TaskSnapshot.Task(task,component,mode,visible,focused,pinned,40,80,300,280);}
    private void publish(TaskSnapshot.Task... rows){List<TaskSnapshot.Task> list=Arrays.asList(rows);tasks.publish(session,new TaskSnapshot(++generation,session.outputEpoch(),id,list,list,true,false,false));invoke("tasksChanged");}
    private TextClock clock(View v){if(v instanceof TextClock)return (TextClock)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextClock c=clock(((ViewGroup)v).getChildAt(i));if(c!=null)return c;}return null;}
    private void identityAndState(){
        HorizontalScrollView bar=(HorizontalScrollView)get("dock");check(bar.isAttachedToWindow(),"Native taskbar not attached");
        Object token=bar.getWindowToken(),menu=get("menu"),windowContext=bar.getContext();TextClock clock=clock(bar);float clockSize=clock.getTextSize();
        View first=null;
        for(int step=0;step<6;step++){
            publish(task(401,true,step%2==0,step>=3,step>=4?1:5),task(402,step!=2,step%2!=0,false,5));
            check(get("dock")==bar&&bar.getWindowToken()==token&&bar.getContext()==windowContext&&get("menu")==menu,"Task event replaced Window/token/context/Start owner");
            check(clock(bar)==clock&&clock.getTextSize()==clockSize,"Task event recreated/rescaled clock");
            LinearLayout entries=(LinearLayout)get("taskbarEntries");check(entries.getChildCount()==8,"Task entries missing/duplicated pinned apps");
            View item=entries.getChildAt(6);check(item.isSelected()==(step%2==0),"Focus indicator stale");
            TextView mark=(TextView)((ViewGroup)item).getChildAt(1);check(mark.getText().toString().equals(step>=3?"↑":"━"),"Pin/visibility mark stale");
            if(step==0)first=item;else check(item!=first,"Task callback retained obsolete click closure");
            float size=mark.getTextSize();invoke("tasksChanged");check(entries.getChildAt(6)==item&&mark.getTextSize()==size,"Unchanged snapshot rebuilt/rescaled rows");
        }
        bar.scrollTo(120,0);publish(task(401,true,true,false,5),task(402,true,false,false,5));
        check(bar.getScrollX()==120,"Immediate update reset scroll position");
    }
    private void motion(int action){long time=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(time,time,action,8,8,0);try{((View)get("dock")).dispatchTouchEvent(event);}finally{event.recycle();}}
    private void inputAndPanels()throws Exception {
        final View[] held={null};
        main(()->{LinearLayout entries=(LinearLayout)get("taskbarEntries");held[0]=entries.getChildAt(6);motion(MotionEvent.ACTION_DOWN);publish(task(405,true,true,false,5));check(entries.getChildAt(6)==held[0]&&(Boolean)get("taskbarRefreshPending"),"Pointer-down row/closure changed");motion(MotionEvent.ACTION_CANCEL);});
        settle();main(()->{check(((LinearLayout)get("taskbarEntries")).getChildAt(6)!=held[0]&&!(Boolean)get("taskbarRefreshPending"),"Input release did not flush latest snapshot");
            surface.toggleStart();check(surface.isStartOpen(),"Owned Start failed to open");LinearLayout entries=(LinearLayout)get("taskbarEntries");held[0]=entries.getChildAt(6);publish(task(406,true,true,false,5));check(entries.getChildAt(6)==held[0],"Start-open refresh replaced row");((AppMenu)get("menu")).close();});
        settle();main(()->{check(!surface.isStartOpen()&&((LinearLayout)get("taskbarEntries")).getChildAt(6)!=held[0]&&!(Boolean)get("taskbarRefreshPending"),"Start close did not flush pending update");
            LinearLayout entries=(LinearLayout)get("taskbarEntries");held[0]=entries.getChildAt(6);check(held[0].performLongClick(),"Context menu did not open");check(get("taskbarPopup")!=null,"Popup owner not retained");publish(task(407,true,true,false,5));check(entries.getChildAt(6)==held[0],"Popup anchor removed during update");((PopupMenu)get("taskbarPopup")).dismiss();});
        settle();main(()->check(((LinearLayout)get("taskbarEntries")).getChildAt(6)!=held[0]&&!(Boolean)get("taskbarRefreshPending"),"Popup dismissal did not flush latest snapshot"));
    }
    private final class FixtureContext extends ContextWrapper implements ShellSettings.Provider,TaskState.Provider {
        FixtureContext(Context base){super(base);}
        public ShellSettings shellSettings(){if(settings==null)settings=ShellSettings.isolated(getSharedPreferences("desktop",0));return settings;}
        public TaskState taskState(){if(tasks==null)tasks=TaskState.isolated(this);return tasks;}
        @Override public Context getApplicationContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){String key=prefix+name;preferenceFiles.add(key);return actual.getSharedPreferences(key,mode);}
        @Override public Context createDisplayContext(Display d){return new FixtureContext(super.createDisplayContext(d));}
        @Override public Context createWindowContext(int type,Bundle options){return new FixtureContext(super.createWindowContext(type,options));}
        @Override public void startActivity(Intent intent){throw new AssertionError("No fixture app launches");}
        @Override public void startActivity(Intent intent,Bundle options){throw new AssertionError("No fixture app launches");}
    }
}
