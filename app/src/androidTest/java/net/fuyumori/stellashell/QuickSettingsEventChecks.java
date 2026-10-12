package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

/** Lifecycle fixtures and passive API registration: no Activity launch or setting mutation. */
final class QuickSettingsEventChecks {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void main(Instrumentation test,Runnable work){
        Throwable[] failure={null};
        test.runOnMainSync(()->{try{work.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private static class Backend implements QuickSettingsEvents.Backend {
        QuickSettingsEvents.Listener listener;
        int starts,stops;
        public void start(QuickSettingsEvents.Listener next){
            check(listener==null,"Duplicate Quick Settings subscription");
            listener=next;starts++;
            next.changed(QuickSettingsEvents.NETWORK);
        }
        public void stop(QuickSettingsEvents.Listener previous){
            check(listener==previous,"Quick Settings detached the wrong subscription");
            listener=null;stops++;
        }
        void event(int fields){check(listener!=null,"Missing Quick Settings subscription");listener.changed(fields);}
    }
    static void run(Instrumentation test) throws Exception {
        check(Looper.myLooper()!=Looper.getMainLooper(),"Quick Settings checks must not block main");
        Backend backend=new Backend();
        List<Integer> updates=new ArrayList<>();
        QuickSettingsEvents[] events={null};
        QuickSettingsEvents.Listener[] old={null};
        long[] epoch={0};
        try{
            main(test,()->{
                events[0]=new QuickSettingsEvents(backend,updates::add);
                events[0].changed(QuickSettingsEvents.ALL);
                events[0].start();events[0].start();epoch[0]=events[0].generation();
                check(backend.starts==1,"Resume subscribed more than once");
                check(events[0].current(epoch[0]),"Active command completion token rejected");
            });
            test.waitForIdleSync();
            main(test,()->check(updates.size()==1&&updates.get(0)==QuickSettingsEvents.ALL,
                    "Resume did not coalesce subscription events into one complete initial read"));
            // The former 1500 ms loop would have delivered another update during this wait.
            Thread.sleep(1700);test.waitForIdleSync();
            main(test,()->{
                check(updates.size()==1,"Idle Quick Settings still polls");
                backend.event(QuickSettingsEvents.AUDIO);backend.event(QuickSettingsEvents.AUDIO);
                events[0].changed(0);
            });
            test.waitForIdleSync();
            main(test,()->{
                check(updates.size()==2&&updates.get(1)==QuickSettingsEvents.AUDIO,
                        "Audio burst was not one audio-only read");
                backend.event(QuickSettingsEvents.NETWORK);backend.event(QuickSettingsEvents.SCREEN);
                backend.event(QuickSettingsEvents.LIFETIME);
            });
            test.waitForIdleSync();
            main(test,()->{
                check(updates.size()==3&&updates.get(2)==(QuickSettingsEvents.NETWORK|QuickSettingsEvents.SCREEN|QuickSettingsEvents.LIFETIME),
                        "Network, Bridge and lifetime events lost fields or spuriously reread audio");
                backend.event(QuickSettingsEvents.ALL);old[0]=backend.listener;
                events[0].stop();events[0].stop();old[0].changed(QuickSettingsEvents.ALL);
                events[0].changed(QuickSettingsEvents.ALL);
                check(backend.stops==1&&!events[0].current(epoch[0]),
                        "Pause did not detach once and invalidate command completion");
            });
            test.waitForIdleSync();
            main(test,()->{
                check(updates.size()==3,"Pause delivered a queued or late update");
                events[0].start();
                check(backend.starts==2,"Reopen did not resubscribe");
                check(!events[0].current(epoch[0]),"Reopen revived a pre-pause completion");
                old[0].changed(QuickSettingsEvents.ALL);
            });
            test.waitForIdleSync();
            main(test,()->{
                check(updates.size()==4&&updates.get(3)==QuickSettingsEvents.ALL,
                        "Reopen did not read fresh state exactly once");
                old[0].changed(QuickSettingsEvents.ALL);
            });
            test.waitForIdleSync();
            main(test,()->{
                check(updates.size()==4,"Obsolete subscription updated a reopened surface");
                backend.event(QuickSettingsEvents.ALL);old[0]=backend.listener;
                events[0].close();events[0].start();old[0].changed(QuickSettingsEvents.ALL);
                check(backend.stops==2&&backend.starts==2,"Destroy retained or recreated subscriptions");
            });
            test.waitForIdleSync();
            main(test,()->check(updates.size()==4,"Destroyed Quick Settings delivered a late update"));
        }finally{main(test,()->{if(events[0]!=null)events[0].close();});}
        partialStart(test);
        installedSubscriptions(test);
    }
    private static void partialStart(Instrumentation test){
        Backend backend=new Backend(){
            @Override public void start(QuickSettingsEvents.Listener listener){
                super.start(listener);
                throw new SecurityException("Synthetic failure after partial registration");
            }
        };
        int[] updates={0};
        main(test,()->{
            QuickSettingsEvents events=new QuickSettingsEvents(backend,fields->updates[0]++);
            try{
                try{events.start();throw new AssertionError("Partial-start failure was swallowed");}
                catch(SecurityException expected){}
                check(backend.starts==1&&backend.stops==1&&backend.listener==null,
                        "Failed startup leaked its partial subscription");
                check(!events.current(events.generation()),"Failed startup retained an active completion token");
                events.changed(QuickSettingsEvents.ALL);
            }finally{events.close();}
        });
        test.waitForIdleSync();
        main(test,()->check(updates[0]==0,"Failed startup delivered its queued initial event"));
    }
    private static void installedSubscriptions(Instrumentation test){
        QuickSettingsEvents.AndroidBackend[] backend={null};
        QuickSettingsEvents[] events={null};
        int[] updates={0},stopped={0};
        try{
            main(test,()->{
                backend[0]=new QuickSettingsEvents.AndroidBackend(test.getTargetContext(),0);
                events[0]=new QuickSettingsEvents(backend[0],fields->updates[0]++);
                events[0].start();
                check(backend[0].registered(),"Installed Quick Settings could not register all public API observers");
            });
            test.waitForIdleSync();
            main(test,()->{
                check(updates[0]>0,"Installed observers did not deliver initial state");
                events[0].stop();stopped[0]=updates[0];
                check(!backend[0].registered(),"Paused installed observers remained registered");
            });
            test.waitForIdleSync();
            main(test,()->{
                check(updates[0]==stopped[0],"Paused installed observers delivered a late update");
                events[0].start();
                check(backend[0].registered(),"Installed observers did not re-register after resume");
            });
            test.waitForIdleSync();
            main(test,()->{
                check(updates[0]>stopped[0],"Installed resume did not reread initial state");
                events[0].close();stopped[0]=updates[0];
                check(!backend[0].registered(),"Destroyed installed observers remained registered");
            });
            test.waitForIdleSync();
            main(test,()->check(updates[0]==stopped[0],"Destroyed installed observers delivered a late update"));
        }finally{main(test,()->{if(events[0]!=null)events[0].close();});}
    }
}
