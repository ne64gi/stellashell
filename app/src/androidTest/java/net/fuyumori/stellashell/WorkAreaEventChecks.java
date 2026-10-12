package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.graphics.Rect;

/** Synthetic display IDs only; no display, window, preference or device mutations. */
final class WorkAreaEventChecks {
    private WorkAreaEventChecks() {}
    static void run(Instrumentation test) {
        Throwable[] failure={null};
        test.runOnMainSync(()->{try{verify();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private static WorkArea area(boolean landscape,int bottomInset){
        int width=landscape?800:400,height=landscape?400:800;
        return new WorkArea(new Rect(0,0,width,height),
                new Rect(0,0,width,height-bottomInset),false,32,60);
    }
    private static void verify(){
        int first=-21001,second=-21002;
        try{
            WorkArea portrait=area(false,0);
            check(WorkArea.update(first,portrait),"First observation was not published");
            check(WorkArea.get(null,first)==portrait,"First geometry is not cached");
            for(int i=0;i<100;i++)check(!WorkArea.update(first,area(false,0)),"Unchanged geometry published again");
            check(WorkArea.get(null,first)==portrait,"Unchanged event replaced the shared geometry");
            check(WorkArea.update(second,area(true,0)),"Second display not independent");
            for(int i=0;i<100;i++){
                check(!WorkArea.update(first,area(false,0)),"Alternating display invalidated portrait");
                check(!WorkArea.update(second,area(true,0)),"Alternating display invalidated landscape");
            }
            check(WorkArea.update(first,area(true,0)),"Rotation was dropped");
            WorkArea ime=area(true,120);ime.imeVisible=true;
            check(WorkArea.update(first,ime),"IME resize was dropped");
            check(!WorkArea.update(second,area(true,0)),"IME affected a different display");
            WorkArea gesture=area(true,120);gesture.imeVisible=true;gesture.gestureLeft=24;
            check(WorkArea.update(first,gesture),"Gesture inset-only change was dropped");
            check(WorkArea.update(first,area(false,0)),"Restored portrait was dropped");
            WorkArea.remove(first);
            check(WorkArea.update(first,area(false,0)),"Reattached observer lost its initial publication");
        }finally{WorkArea.remove(first);WorkArea.remove(second);}
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
