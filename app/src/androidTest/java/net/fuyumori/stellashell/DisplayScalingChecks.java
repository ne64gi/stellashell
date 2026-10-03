package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Real Binder density operations only on DockLayoutChecks' already-owned public fixture. */
final class DisplayScalingChecks {
    private final Instrumentation test;private final Context context;
    DisplayScalingChecks(Instrumentation test){this.test=test;context=test.getTargetContext();}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private String call(Bridge.Work work)throws Exception{
        CountDownLatch done=new CountDownLatch(1);String[] answer=new String[2];
        test.runOnMainSync(()->Bridge.get(context).call(work,(result,error)->{answer[0]=result;answer[1]=error;done.countDown();}));
        require(done.await(15,TimeUnit.SECONDS),"Display scaling Bridge timeout");require(answer[1]==null,"Display scaling Bridge failure: "+answer[1]);return answer[0];
    }
    private JSONObject snapshot(int id)throws Exception{return new JSONObject(call(service->service.snapshotDisplayScale(id)));}
    private JSONObject apply(int id,JSONObject before,int percent)throws Exception{
        return new JSONObject(call(service->service.applyDisplayScale(id,before.getString("identity"),percent)));
    }
    private void ready()throws Exception{
        test.runOnMainSync(()->Bridge.get(context).connect());long until=SystemClock.uptimeMillis()+10000;
        while(!Bridge.get(context).ready()&&SystemClock.uptimeMillis()<until)Thread.sleep(100);
        require(Bridge.get(context).ready(),"Display scaling requires the real Shizuku Bridge");
    }
    private static void sameDensity(JSONObject before,JSONObject after,String message)throws Exception{
        require(before.getInt("physicalDensity")==after.getInt("physicalDensity")&&before.getInt("density")==after.getInt("density")&&before.getInt("overrideDensity")==after.getInt("overrideDensity"),message);
    }
    private static void sameResolution(JSONObject before,JSONObject after)throws Exception{
        require(before.getInt("width")==after.getInt("width")&&before.getInt("height")==after.getInt("height"),"Density operation changed fixture resolution");
    }
    void run(int ownedDisplay)throws Exception{
        Display display=context.getSystemService(DisplayManager.class).getDisplay(ownedDisplay);
        require(ownedDisplay>0&&display!=null&&display.isValid()&&(display.getFlags()&Display.FLAG_PRIVATE)==0&&display.getName().startsWith("StellaShell owned"),"Only the caller's owned public fixture may be scaled");
        ready();JSONObject mainBefore=snapshot(0),before=snapshot(ownedDisplay),latest=before;
        int originalPercent=before.getBoolean("standard")?100:before.getInt("percent");
        // An unusual pre-existing override must be exactly expressible by this
        // bounded API; otherwise refuse before touching even the fixture.
        require(originalPercent>=ScreenScalePolicy.MIN&&originalPercent<=ScreenScalePolicy.MAX&&ScreenScalePolicy.targetDensity(before.getInt("physicalDensity"),originalPercent)==before.getInt("density"),"Fixture's previous density cannot be restored exactly");
        Throwable failure=null;
        try{
            for(int percent:new int[]{125,150}){
                latest=apply(ownedDisplay,latest,percent);
                require(latest.getInt("density")==ScreenScalePolicy.targetDensity(before.getInt("physicalDensity"),percent),"Owned display density did not change to requested scale");
                require(latest.getInt("percent")==percent,"Owned display scale percentage differs");sameResolution(before,latest);
                sameDensity(mainBefore,snapshot(0),"Scaling an external fixture changed main-display density");
            }
            latest=apply(ownedDisplay,latest,100);require(latest.getBoolean("standard")&&latest.getInt("overrideDensity")==0&&latest.getInt("density")==before.getInt("physicalDensity"),"Reset did not restore the physical baseline");sameResolution(before,latest);
            // A stale token must be rejected without changing the current fixture.
            String stale=before.getString("identity");JSONObject reset=latest;
            CountDownLatch rejected=new CountDownLatch(1);String[] error={null};
            test.runOnMainSync(()->Bridge.get(context).call(service->service.applyDisplayScale(ownedDisplay,stale,150),(result,problem)->{error[0]=problem;rejected.countDown();}));
            require(rejected.await(15,TimeUnit.SECONDS)&&error[0]!=null,"Stale snapshot was not rejected");
            sameDensity(reset,snapshot(ownedDisplay),"Rejected stale operation changed fixture density");
        }catch(Throwable problem){failure=problem;}
        finally{
            try{
                Display current=context.getSystemService(DisplayManager.class).getDisplay(ownedDisplay);
                require(current!=null&&current.isValid()&&display.getName().equals(current.getName()),"Owned fixture disappeared before restoration");
                JSONObject actual=snapshot(ownedDisplay);
                require(before.getString("screenIdentity").equals(actual.getString("screenIdentity")),"Owned fixture identity changed; do not restore a replacement display");
                if(actual.getInt("density")!=before.getInt("density")||actual.getInt("overrideDensity")!=before.getInt("overrideDensity"))actual=apply(ownedDisplay,actual,originalPercent);
                sameDensity(before,actual,"Owned fixture density was not restored exactly");sameResolution(before,actual);
            }catch(Throwable restore){if(failure==null)failure=restore;else failure.addSuppressed(restore);}
            try{sameDensity(mainBefore,snapshot(0),"Display scaling changed main-display density");}
            catch(Throwable primary){if(failure==null)failure=primary;else failure.addSuppressed(primary);}
        }
        if(failure instanceof Exception)throw (Exception)failure;if(failure instanceof Error)throw (Error)failure;if(failure!=null)throw new AssertionError(failure);
    }
}
