package net.fuyumori.stellashell;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.*;
import android.content.res.Configuration;
import android.os.*;
import android.util.Rational;

/** Disposable PiP-only fixture. Cross-process controls are nonce-scoped, never user app controls. */
public final class PipFixtureActivity extends Activity {
    static final String CONTROL="net.fuyumori.stellashell.test.PIP_CONTROL";
    static final String STATE="net.fuyumori.stellashell.test.PIP_STATE";
    static final String TOKEN="fixture_token",REPLY_PACKAGE="reply_package",COMMAND="command";
    static final String ENTER="enter",FINISH="finish",QUERY="query",REQUEST="request";
    private String token,replyPackage,lifecycle="created";private boolean registered,resumed,callbackPip,callbackKnown;
    private long sequence;
    private final BroadcastReceiver control=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){
        if(!token.equals(intent.getStringExtra(TOKEN)))return;
        String command=intent.getStringExtra(COMMAND);
        if(QUERY.equals(command)){reply(command,true,null,intent.getLongExtra(REQUEST,-1));}
        else if(FINISH.equals(command)){reply(command,true,null);finishAndRemoveTask();}
        else if(ENTER.equals(command)){
            try{
                boolean accepted=enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(16,9)).build());
                reply(command,accepted,null);
            }catch(RuntimeException error){reply(command,false,error.getClass().getSimpleName());}
        }
    }};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);token=getIntent().getStringExtra(TOKEN);replyPackage=getIntent().getStringExtra(REPLY_PACKAGE);
        if(token==null||token.isEmpty()||replyPackage==null||replyPackage.isEmpty()){finishAndRemoveTask();return;}
        android.view.View view=new android.view.View(this);view.setBackgroundColor(0xff284860);setContentView(view);
        IntentFilter filter=new IntentFilter(CONTROL);
        registerControl(filter);
        registered=true;
        reply("created",true,null);
    }
    // API 30–32 have no exported flag semantics. This cross-package test
    // receiver is intentionally exported; every control must carry its nonce.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerControl(IntentFilter filter){
        if(Build.VERSION.SDK_INT>=33)getApplicationContext().registerReceiver(control,filter,Context.RECEIVER_EXPORTED);
        else getApplicationContext().registerReceiver(control,filter);
    }
    @Override public void onStart(){super.onStart();lifecycle="started";reply("started",true,null);}
    @Override public void onResume(){super.onResume();resumed=true;lifecycle="resumed";reply("resumed",true,null);}
    @Override public void onPause(){resumed=false;lifecycle="paused";reply("paused",true,null);super.onPause();}
    @Override public void onStop(){resumed=false;lifecycle="stopped";reply("stopped",true,null);super.onStop();}
    @Override public void onConfigurationChanged(Configuration configuration){
        super.onConfigurationChanged(configuration);reply("configuration",true,null);
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);reply("focus",true,null);}
    @Override public void onPictureInPictureModeChanged(boolean inPip,Configuration configuration){
        super.onPictureInPictureModeChanged(inPip,configuration);callbackKnown=true;callbackPip=inPip;reply("mode",true,null);
    }
    private void reply(String event,boolean accepted,String error){reply(event,accepted,error,-1);}
    private void reply(String event,boolean accepted,String error,long request){
        if(token==null||replyPackage==null)return;
        Configuration config=getResources().getConfiguration();android.view.View decor=getWindow().getDecorView();
        Intent intent=new Intent(STATE).setPackage(replyPackage).putExtra(TOKEN,token).putExtra("event",event)
            .putExtra("accepted",accepted).putExtra("task_id",getTaskId()).putExtra("display_id",getDisplay()==null?-1:getDisplay().getDisplayId())
            .putExtra("pip",isInPictureInPictureMode()).putExtra("resumed",resumed)
            .putExtra(REQUEST,request).putExtra("sequence",++sequence).putExtra("uptime",SystemClock.uptimeMillis())
            .putExtra("lifecycle",lifecycle).putExtra("callback_known",callbackKnown).putExtra("callback_pip",callbackPip)
            .putExtra("focus",hasWindowFocus()).putExtra("finishing",isFinishing()).putExtra("destroyed",isDestroyed())
            .putExtra("attached",decor.isAttachedToWindow()).putExtra("shown",decor.isShown())
            .putExtra("width",decor.getWidth()).putExtra("height",decor.getHeight())
            .putExtra("screen_width_dp",config.screenWidthDp).putExtra("screen_height_dp",config.screenHeightDp)
            .putExtra("density_dpi",config.densityDpi).putExtra("orientation",config.orientation);
        if(error!=null)intent.putExtra("error",error);getApplicationContext().sendBroadcast(intent);
    }
    @Override public void onDestroy(){
        resumed=false;lifecycle="destroyed";reply("destroyed",true,null);
        if(registered){getApplicationContext().unregisterReceiver(control);registered=false;}
        super.onDestroy();
    }
}
