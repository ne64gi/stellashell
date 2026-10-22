package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Bundle;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owned overlay/search windows, nonce preferences, no personal app/task/input content. */
final class PhoneImeChecks {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static Object get(Object owner,String name){
        try{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static void main(Instrumentation test,Runnable action){
        Throwable[] error={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable t){error[0]=t;}});
        if(error[0]!=null)throw new AssertionError(error[0]);
    }
    private static WindowManager.LayoutParams params(View v){return (WindowManager.LayoutParams)v.getLayoutParams();}
    private static void hidden(PhoneSidebar dock,PhoneTaskbar bar){
        View panel=(View)get(bar,"panel");
        check(params(panel).alpha==0,"Taskbar still draws over IME");
        check((params(panel).flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0,"Hidden taskbar still intercepts keys");
        check(panel.getImportantForAccessibility()==View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,"Hidden taskbar accessible");
        check(((View)get(dock,"panel")).getVisibility()!=View.VISIBLE,"Dock stayed open over IME");
        for(View h:dock.handles)check(h.getVisibility()!=View.VISIBLE&&(params(h).flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0,"Hidden handle still visible/touchable");
        dock.show(true);dock.showGesture(.8f,.7f,true);
        check(((View)get(dock,"panel")).getVisibility()!=View.VISIBLE,"IME allowed Dock to reopen");
    }
    private static void restored(PhoneSidebar dock,PhoneTaskbar bar){
        check(params((View)get(bar,"panel")).alpha==1,"Taskbar did not restore");
        check((params((View)get(bar,"panel")).flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)==0,"Restored taskbar not touchable");
        check(dock.handles.stream().anyMatch(h->h.getVisibility()==View.VISIBLE&&(params(h).flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)==0),"Handles did not restore");
        check(((View)get(dock,"panel")).getVisibility()!=View.VISIBLE,"IME dismissal unexpectedly reopened Dock");
    }
    static void run(Instrumentation test)throws Exception{
        Context actual=test.getTargetContext();Sandbox sandbox=new Sandbox(actual);
        PhoneSidebar[] dock={null};PhoneTaskbar[] bar={null};WorkAreaObserver[] witness={null},externalObserver={null};
        ImageReader[] reader={null};VirtualDisplay[] external={null};int[] externalEvents={0};
        CountDownLatch[] shown={new CountDownLatch(1)},hidden={new CountDownLatch(1)};
        boolean[] seen={false};EditText[] search={null};
        WorkArea[] previous={null};
        try{
            main(test,()->{
                check(!actual.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked(),"Unlock device before real IME check");
                check(android.provider.Settings.canDrawOverlays(actual),"Existing overlay grant required");
                previous[0]=WorkArea.get(actual,0);
                Launches.prefs(sandbox).edit().putBoolean("phone_profile_initialized",true).putBoolean("enabled",false)
                    .putBoolean("phone_sidebar",true).putBoolean("phone_sidebar_over_apps",true).putString("phone_sidebar_side","both")
                    .putBoolean("phone_dock_landscape_gesture",false).putBoolean("phone_taskbar",true).commit();
                bar[0]=new PhoneTaskbar(sandbox,()->{},new Feed());
                dock[0]=new PhoneSidebar(sandbox,()->{},new Feed(),null);dock[0].taskbarReady(true);
                Context window=((View)get(bar[0],"panel")).getContext();
                witness[0]=new WorkAreaObserver(window,0,()->{},visible->{
                    seen[0]=visible;if(visible)shown[0].countDown();else hidden[0].countDown();
                });
                reader[0]=ImageReader.newInstance(640,480,PixelFormat.RGBA_8888,2);
                external[0]=actual.getSystemService(DisplayManager.class).createVirtualDisplay("Stella owned IME fixture",640,480,160,reader[0].getSurface(),DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
                check(external[0]!=null,"Owned private display unavailable");
                Display display=external[0].getDisplay();Context other=sandbox.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
                externalObserver[0]=new WorkAreaObserver(other,display.getDisplayId(),()->{},visible->externalEvents[0]++);
                WindowInsets fakeIme=new WindowInsets.Builder().setVisible(WindowInsets.Type.ime(),true).setInsets(WindowInsets.Type.ime(),Insets.of(0,0,0,240)).build();
                ((View)get(externalObserver[0],"probe")).dispatchApplyWindowInsets(fakeIme);
            });
            test.waitForIdleSync();
            main(test,()->{
                check(!seen[0],"Close existing keyboard before fixture");
                check(externalEvents[0]==0,"Phone IME policy leaked to external observer");
                dock[0].show(true);dock[0].keyboardVisible(true);bar[0].keyboardVisible(true);hidden(dock[0],bar[0]);
                dock[0].rebuild();bar[0].rebuild();hidden(dock[0],bar[0]);
                dock[0].keyboardVisible(false);bar[0].keyboardVisible(false);restored(dock[0],bar[0]);
                bar[0].toggleStart();search[0]=(EditText)get(bar[0].menu,"search");
            });
            test.waitForIdleSync();
            main(test,()->{
                shown[0]=new CountDownLatch(1);hidden[0]=new CountDownLatch(1);
                check(search[0].requestFocus(),"Search field did not take focus");
                search[0].getContext().getSystemService(InputMethodManager.class).showSoftInput(search[0],InputMethodManager.SHOW_IMPLICIT);
            });
            check(shown[0].await(10,TimeUnit.SECONDS),"Real IME show event not delivered to transparent observer");
            test.waitForIdleSync();
            main(test,()->{
                hidden(dock[0],bar[0]);check(bar[0].menu.isOpen()&&search[0].hasFocus(),"IME suppression dismissed Start search");
                search[0].setText("stella-ime-fixture");
                check(bar[0].menu.isOpen()&&search[0].hasFocus(),"Search input lost after IME hide policy");
                hidden[0]=new CountDownLatch(1);
                search[0].getWindowInsetsController().hide(WindowInsets.Type.ime());
            });
            check(hidden[0].await(10,TimeUnit.SECONDS),"Real IME hide event was lost");
            test.waitForIdleSync();
            main(test,()->{
                restored(dock[0],bar[0]);check(bar[0].menu.isOpen(),"IME dismissal closed Start");
                // Queue a visibility transition, then detach before its posted callback.
                WorkAreaObserver observer=(WorkAreaObserver)get(dock[0],"observer");
                ((View)get(observer,"probe")).dispatchApplyWindowInsets(new WindowInsets.Builder().setVisible(WindowInsets.Type.ime(),true).build());
                dock[0].close();bar[0].close();
            });
            test.waitForIdleSync();
            main(test,()->check(!dock[0].ready()&&!bar[0].ready(),"Late IME callback resurrected closed navigation"));
        }finally{
            main(test,()->{
                if(search[0]!=null&&search[0].getWindowInsetsController()!=null)search[0].getWindowInsetsController().hide(WindowInsets.Type.ime());
                if(dock[0]!=null)dock[0].close();if(bar[0]!=null)bar[0].close();
                if(witness[0]!=null)witness[0].close();if(externalObserver[0]!=null)externalObserver[0].close();
                if(external[0]!=null)external[0].release();if(reader[0]!=null)reader[0].close();
                if(previous[0]!=null)WorkArea.put(0,previous[0]);sandbox.cleanup();
            });
        }
    }
    private static final class Feed implements PhoneRunningTasks.Backend{
        public boolean ready(){return true;}public void observe(Runnable r){}public void remove(Runnable r){}
        public void snapshot(Bridge.Reply reply){reply.done("{\"tasks\":[]}",null);}
        public void focus(TaskSnapshot.Task task,Bridge.Reply reply){throw new AssertionError("No personal task operation");}
        public void operation(TaskSnapshot.Task task,String action,Rect bounds,Bridge.Reply reply){throw new AssertionError("No personal task operation");}
    }
    private static final class Sandbox extends ContextWrapper implements ShellSettings.Provider,TaskState.Provider{
        final Sandbox owner;final Context actual;final String prefix;final Set<String> files;
        ShellSettings settings;TaskState tasks;
        Sandbox(Context actual){super(actual);this.actual=actual;owner=this;prefix="phone_ime_fixture_"+UUID.randomUUID()+"_";files=new HashSet<>();}
        Sandbox(Context base,Sandbox owner){super(base);this.owner=owner;actual=owner.actual;prefix=owner.prefix;files=owner.files;}
        public ShellSettings shellSettings(){if(owner.settings==null)owner.settings=ShellSettings.isolated(getSharedPreferences("desktop",0));return owner.settings;}
        public TaskState taskState(){if(owner.tasks==null)owner.tasks=TaskState.isolated(this);return owner.tasks;}
        @Override public Context getApplicationContext(){return owner;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){String file=prefix+name;files.add(file);return actual.getSharedPreferences(file,mode);}
        @Override public Context createDisplayContext(Display d){return new Sandbox(super.createDisplayContext(d),owner);}
        @Override public Context createWindowContext(int type,Bundle options){return new Sandbox(super.createWindowContext(type,options),owner);}
        @Override public void startActivity(Intent intent){throw new AssertionError("Fixture must not launch apps");}
        @Override public void startActivity(Intent intent,Bundle options){throw new AssertionError("Fixture must not launch apps");}
        void cleanup(){if(owner.settings!=null)owner.settings.close();if(owner.tasks!=null)owner.tasks.closeOwner();for(String file:files)actual.deleteSharedPreferences(file);}
    }
}
