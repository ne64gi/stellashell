package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.util.HashMap;
import java.util.Map;

/** Native preference/owner boundary fixture; injected transport is not real Shizuku proof. */
final class PowerModeChecks {
    private PowerModeChecks(){}
    static void run(Instrumentation test)throws Exception {
        Throwable[] failure={null};
        test.runOnMainSync(()->{try{verify(test.getTargetContext());}catch(Throwable error){failure[0]=error;}});
        if(failure[0] instanceof Exception)throw (Exception)failure[0];
        if(failure[0] instanceof Error)throw (Error)failure[0];
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private static void verify(Context base){
        String prefix="power_mode_boundary_"+System.nanoTime();
        String[] stores={prefix+"_fresh",prefix+"_legacy",prefix+"_explicit",prefix+"_toggle",prefix+"_persistence"};
        try{
            fresh(base,stores[0]);legacy(base,stores[1]);explicitOff(base,stores[2]);toggleAndCleanup(base,stores[3]);persistenceFailure(base,stores[4]);PowerWorkspaceChecks.verify(base);
        }finally{
            for(String name:stores)check(base.deleteSharedPreferences(name),"Nonce preference store could not be removed");
        }
    }
    private static void fresh(Context base,String name){
        FakeBackend backend=new FakeBackend();PowerMode owner=PowerMode.forStore(base,name,backend);
        SharedPreferences prefs=base.getSharedPreferences(name,Context.MODE_PRIVATE);
        check(!owner.requested()&&!owner.active()&&!owner.transition(),"Fresh install must stay OFF");
        check(prefs.contains("power_mode")&&!prefs.getBoolean("power_mode",true),"Fresh OFF intent was not persisted");
        owner.connect();owner.resumeHome();check(backend.calls()==0,"Fresh OFF connected a privileged backend");
        NonceContext context=new NonceContext(base,name,owner);
        check(PowerMode.of(context)==owner,"Provider seam escaped to the production owner");
        PowerMode.initializeLegacy(context);check(!owner.requested(),"Initialization overrode explicit OFF");
        // Read only the device's existing grant. No permission or AppOps state is fabricated/changed.
        check(PrerequisitesActivity.satisfied(context)==Settings.canDrawOverlays(context),"Base prerequisites gained a Power Mode requirement");
        check(backend.calls()==0,"Base prerequisite check invoked Power backend");
    }
    private static void legacy(Context base,String name){
        SharedPreferences prefs=base.getSharedPreferences(name,Context.MODE_PRIVATE);
        seedNormal(prefs);check(prefs.edit().putString("before_desktop","1").putString("before_freeform","null").commit(),"Seed failed");
        Map<String,?> before=normalValues(prefs);FakeBackend backend=new FakeBackend();
        PowerMode owner=PowerMode.forStore(base,name,backend);
        check(owner.requested()&&!owner.active()&&!owner.transition(),"Existing privileged setup intent was not migrated");
        check(before.equals(normalValues(prefs)),"Migration changed a pre-existing setting");
        check(backend.calls()==0,"Migration contacted a privileged backend");
        PowerMode reloaded=PowerMode.forStore(base,name,new FakeBackend());
        check(reloaded.requested()&&before.equals(normalValues(prefs)),"Migration was not stable on owner reload");
    }
    private static void explicitOff(Context base,String name){
        SharedPreferences prefs=base.getSharedPreferences(name,Context.MODE_PRIVATE);seedNormal(prefs);
        check(prefs.edit().putBoolean("power_mode",false).commit(),"Seed failed");
        Map<String,?> before=normalValues(prefs);FakeBackend backend=new FakeBackend();PowerMode owner=PowerMode.forStore(base,name,backend);
        check(!owner.requested(),"Legacy keys overrode explicit OFF");
        Reply reply=new Reply();owner.setRequested(false,reply);owner.connect();owner.resumeHome();
        check(reply.calls==1&&reply.error==null&&backend.calls()==0,"Repeated OFF did not remain idempotent");
        check(before.equals(normalValues(prefs))&&!owner.transition(),"Repeated OFF changed base settings or created cleanup debt");
    }
    private static void toggleAndCleanup(Context base,String name){
        SharedPreferences prefs=base.getSharedPreferences(name,Context.MODE_PRIVATE);seedNormal(prefs);
        check(prefs.edit().putBoolean("power_mode",false).commit(),"Seed failed");
        Map<String,?> before=normalValues(prefs);FakeBackend backend=new FakeBackend();PowerMode owner=PowerMode.forStore(base,name,backend);
        Reply enabled=new Reply();owner.setRequested(true,enabled);
        check(enabled.calls==1&&enabled.error==null&&backend.on==1&&owner.requested(),"Explicit ON was not accepted");
        check(!owner.active()&&!owner.transition(),"Request acceptance incorrectly implied a connected backend");
        check(before.equals(normalValues(prefs)),"ON changed base settings");
        owner.connect();check(backend.connects==1,"Requested ON did not route the explicit connection");
        owner.connected(true);check(owner.active(),"Connected ON did not expose active capability");
        owner.resumeHome();check(backend.homes==1,"ON did not route the HOME capability adapter");
        long queuedGeneration=owner.generation();check(owner.permits(queuedGeneration),"Active generation was not admitted");
        boolean[] queuedWorkExecuted={false};Runnable queued=()->{if(owner.permits(queuedGeneration))queuedWorkExecuted[0]=true;};
        Reply disabled=new Reply();owner.setRequested(false,disabled);
        check(!owner.requested()&&!owner.active()&&owner.transition(),"OFF did not revoke capability before cleanup reply");
        check(!owner.permits(queuedGeneration),"OFF did not revoke queued work immediately");
        queued.run();check(!queuedWorkExecuted[0],"Stale queued generation executed after OFF");
        owner.connected(true);check(!owner.active(),"A late connected event reactivated OFF");
        check(!prefs.getBoolean("power_mode",true)&&prefs.getBoolean("power_cleanup_pending",false),"OFF cleanup debt was not persisted before completion");
        check(disabled.calls==0&&backend.off==1,"Held cleanup completed early");
        int connectCount=backend.connects,homeCount=backend.homes;owner.connect();owner.resumeHome();
        check(backend.connects==connectCount&&backend.homes==homeCount,"Cleanup reentered the normal Power connection path");
        Reply prematureRetry=new Reply();owner.retry(prematureRetry);
        check(prematureRetry.calls==1&&prematureRetry.error!=null&&backend.retries==0,"Concurrent cleanup admitted a retry");
        Reply prematureOn=new Reply();owner.setRequested(true,prematureOn);
        check(prematureOn.calls==1&&prematureOn.error!=null&&backend.on==1&&!owner.requested(),"Pending cleanup admitted ON");
        check(before.equals(normalValues(prefs)),"OFF stopped or changed the ordinary shell/display selection");
        backend.finish(false);
        check(disabled.calls==1&&disabled.error!=null&&owner.transition(),"Cleanup failure was discarded");
        check(prefs.getBoolean("power_cleanup_pending",false),"Cleanup failure cleared persisted debt");
        FakeBackend retryBackend=new FakeBackend();PowerMode reloaded=PowerMode.forStore(base,name,retryBackend);
        check(!reloaded.requested()&&!reloaded.active()&&reloaded.transition(),"Cleanup debt was not retained across owner reload");
        reloaded.connect();reloaded.resumeHome();check(retryBackend.calls()==0,"Reloaded debt retried implicitly");
        Reply retry=new Reply();reloaded.retry(retry);
        check(retryBackend.retries==1&&retry.calls==0&&reloaded.transition(),"Explicit retry did not hold the release transaction");
        check(before.equals(normalValues(prefs)),"Retry changed base settings");
        retryBackend.finish(true);
        check(retry.calls==1&&retry.error==null&&!reloaded.transition()&&!reloaded.requested(),"Successful retry did not finish OFF");
        check(!prefs.getBoolean("power_cleanup_pending",true),"Successful release left persisted cleanup debt");
        Reply repeat=new Reply();reloaded.retry(repeat);
        check(repeat.calls==1&&repeat.error==null&&retryBackend.retries==1,"Completed OFF retried the backend again");
        PowerMode finalOwner=PowerMode.forStore(base,name,new FakeBackend());
        check(!finalOwner.requested()&&!finalOwner.transition()&&before.equals(normalValues(prefs)),"OFF or base settings changed on final reload");
    }
    private static void persistenceFailure(Context base,String name){
        SharedPreferences prefs=base.getSharedPreferences(name,Context.MODE_PRIVATE);seedNormal(prefs);
        check(prefs.edit().putBoolean("power_mode",false).commit(),"Seed failed");
        FailingPreferences failure=new FailingPreferences(prefs);FakeBackend backend=new FakeBackend();PowerMode owner=PowerMode.forPreferences(failure.proxy,backend);
        Map<String,?> off=new HashMap<>(prefs.getAll());failure.failNext=true;
        Reply rejectedOn=new Reply();owner.setRequested(true,rejectedOn);
        check(rejectedOn.calls==1&&rejectedOn.error!=null&&!owner.requested()&&backend.on==0&&off.equals(prefs.getAll()),"Failed ON persistence leaked intent or contacted backend");
        owner.setRequested(true,new Reply());owner.connected(true);long generation=owner.generation();
        Map<String,?> on=new HashMap<>(prefs.getAll());failure.failNext=true;
        Reply rejectedOff=new Reply();owner.setRequested(false,rejectedOff);
        check(rejectedOff.calls==1&&rejectedOff.error!=null&&owner.active()&&owner.permits(generation)&&backend.off==0&&on.equals(prefs.getAll()),"Failed OFF persistence changed the accepted capability or base settings");
        Reply offReply=new Reply();owner.setRequested(false,offReply);failure.failNext=true;backend.finish(true);
        check(offReply.calls==1&&offReply.error!=null&&!owner.requested()&&owner.transition()&&prefs.getBoolean("power_cleanup_pending",false),"Failed completion persistence erased retryable cleanup debt");
        Reply retry=new Reply();owner.retry(retry);backend.finish(true);
        check(retry.calls==1&&retry.error==null&&!owner.transition()&&!prefs.getBoolean("power_mode",true),"Retry did not recover an injected completion-persistence failure");
    }
    private static void seedNormal(SharedPreferences prefs){
        check(prefs.edit().putBoolean("enabled",true).putBoolean("primary_mode",false).putInt("preferred_display",7).putInt("active_display",7)
                .putBoolean("workspace_auto",true).putBoolean("compact_workspace",true).putBoolean("freeform",true).putBoolean("hide_virtual_ime",true)
                .putString("phone_pins","[]").putInt("phone_dock_scale",125).commit(),"Seed failed");
    }
    private static Map<String,?> normalValues(SharedPreferences prefs){
        Map<String,Object> values=new HashMap<>(prefs.getAll());values.remove("power_mode");values.remove("power_cleanup_pending");return values;
    }
    private static final class NonceContext extends ContextWrapper implements PowerMode.Provider {
        private final String name;private final PowerMode owner;
        NonceContext(Context base,String name,PowerMode owner){super(base);this.name=name;this.owner=owner;}
        @Override public Context getApplicationContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String ignored,int mode){return getBaseContext().getSharedPreferences(name,mode);}
        @Override public PowerMode powerMode(){return owner;}
    }
    private static final class Reply implements Bridge.Reply {
        int calls;String error;
        @Override public void done(String result,String error){calls++;this.error=error;}
    }
    /** Inject commit(false) after the real memory-map update, matching Android's rejected-write edge. */
    private static final class FailingPreferences {
        final SharedPreferences proxy;boolean failNext;
        FailingPreferences(SharedPreferences delegate){
            proxy=(SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(ignored,method,args)->{
                if(!"edit".equals(method.getName()))return method.invoke(delegate,args);
                SharedPreferences.Editor editor=delegate.edit();
                return java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(wrapped,editMethod,editArgs)->{
                    Object result=editMethod.invoke(editor,editArgs);
                    if("commit".equals(editMethod.getName())&&failNext){failNext=false;return false;}
                    return result==editor?wrapped:result;
                });
            });
        }
    }
    private static final class FakeBackend implements PowerMode.Backend {
        int connects,homes,on,off,retries;Bridge.Reply pending;
        @Override public void connect(){connects++;}
        @Override public void resumeHome(){homes++;}
        @Override public void powerChanged(boolean requested,Bridge.Reply reply){
            if(requested){on++;reply.done("OK",null);}else{off++;hold(reply);}
        }
        @Override public void retry(Bridge.Reply reply){retries++;hold(reply);}
        private void hold(Bridge.Reply reply){check(pending==null,"Fixture received overlapping backend releases");pending=reply;}
        void finish(boolean success){Bridge.Reply reply=pending;check(reply!=null,"No fixture release was pending");pending=null;reply.done(success?"OK":null,success?null:"injected cleanup failure");}
        int calls(){return connects+homes+on+off+retries;}
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
