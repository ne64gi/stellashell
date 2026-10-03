package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import java.lang.reflect.Field;

/** Real service/overlay, programmatic display callbacks; no personal app or display-setting changes. */
final class StartMenuStabilityChecks {
    private final Instrumentation test;private final Context context;
    StartMenuStabilityChecks(Instrumentation test){this.test=test;context=test.getTargetContext();}
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private static Field field(Class<?> owner,String name)throws ReflectiveOperationException{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;}
    private void main(Runnable action){Throwable[] failure={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable t){failure[0]=t;}});if(failure[0]!=null)throw new AssertionError(failure[0]);}
    private void await(java.util.function.BooleanSupplier condition)throws Exception{
        long until=SystemClock.uptimeMillis()+10000;
        while(SystemClock.uptimeMillis()<until){boolean[] ok={false};main(()->ok[0]=condition.getAsBoolean());if(ok[0])return;Thread.sleep(100);}
        throw new AssertionError("Phone navigation did not become ready");
    }
    void run()throws Exception{
        check(Launches.prefs(context).getBoolean("enabled",false)&&Launches.prefs(context).getBoolean("primary_mode",false),"Requires an already enabled phone session");
        check(Launches.prefs(context).getInt("workspace_display",0)==0,"Do not move an external workspace for this test");
        main(()->DockService.start(context,0,false));
        await(DockService::phoneNavigationReady);
        DockService service=(DockService)field(DockService.class,"instance").get(null);
        PhoneSidebar sidebar=(PhoneSidebar)field(DockService.class,"phoneSidebar").get(service);
        AppMenu menu=sidebar.menu;
        try{
            main(()->{menu.close();sidebar.toggleStart();check(menu.isOpen(),"Start did not open");});
            View root=(View)field(AppMenu.class,"root").get(menu);
            EditText search=(EditText)field(AppMenu.class,"search").get(menu);
            main(()->{search.setText("zz-start-stability-fixture");search.setSelection(3);});
            // The display dimensions/density/rotation are unchanged. This is also
            // how brightness/refresh-rate-only DisplayManager events arrive.
            for(int i=0;i<8;i++){
                main(()->service.onDisplayChanged(0));test.waitForIdleSync();
                check(menu.isOpen(),"Unchanged main-display event closed Start");
                check(field(AppMenu.class,"root").get(menu)==root,"Unchanged event recreated Start");
                main(()->check("zz-start-stability-fixture".contentEquals(search.getText())&&search.getSelectionStart()==3,"Search state was reset"));
            }
            main(()->{service.onDisplayChanged(-12345);check(menu.isOpen(),"Another display closed Start");sidebar.toggleStart();check(!menu.isOpen(),"Start toggle no longer closes");sidebar.toggleStart();check(menu.isOpen(),"Start did not reopen");menu.back();check(!menu.isOpen(),"Back no longer closes Start");});
            // Emulate a previous geometry snapshot. Real geometry changes must
            // still rebuild once, then use the new baseline for later events.
            main(()->{try{sidebar.toggleStart();field(DockService.class,"displayGeometry").set(service,"previous-geometry");service.onDisplayChanged(0);check(!menu.isOpen(),"Changed geometry did not retire the old menu");sidebar.toggleStart();service.onDisplayChanged(0);check(menu.isOpen(),"New geometry baseline was not captured");}catch(ReflectiveOperationException e){throw new AssertionError(e);}});
        }finally{main(menu::close);}
    }
}
