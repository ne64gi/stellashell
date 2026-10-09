package net.fuyumori.stellashell;

import android.app.Activity;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.*;
import android.view.*;
import java.util.Arrays;

/** Disposable freeform surface: only the caller's nonce can query or finish this instance. */
public final class HomeWindowFixtureActivity extends Activity {
    static final String CONTROL="net.fuyumori.stellashell.test.HOME_WINDOW_CONTROL";
    static final String STATE="net.fuyumori.stellashell.test.HOME_WINDOW_STATE";
    static final String TOKEN="fixture_nonce",OWNER_NONCE="fixture_owner_nonce",REPLY="reply_package",COMMAND="command",REQUEST="request";
    private String token,ownerNonce,replyPackage;private boolean registered,sentinel,resultFixture;private int clicks;
    private boolean tapFocused;private int tapDisplay=-1,tapTask=-1;
    private final BroadcastReceiver controls=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){
        if(!token.equals(intent.getStringExtra(TOKEN))||!ownerNonce.equals(intent.getStringExtra(OWNER_NONCE)))return;
        if("query".equals(intent.getStringExtra(COMMAND)))reply(intent.getLongExtra(REQUEST,-1));
        else if("finish".equals(intent.getStringExtra(COMMAND)))finishAndRemoveTask();
    }};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);token=getIntent().getStringExtra(TOKEN);ownerNonce=getIntent().getStringExtra(OWNER_NONCE);replyPackage=getIntent().getStringExtra(REPLY);
        sentinel=getIntent().getBooleanExtra("fixture_sentinel",false);resultFixture=getIntent().getBooleanExtra("fixture_result",false);
        String suffix=token!=null&&ownerNonce!=null&&token.startsWith(ownerNonce+"-")?token.substring(ownerNonce.length()+1):"";
        if(ownerNonce==null||ownerNonce.isEmpty()||token==null||!Arrays.asList("a","b","sentinel","result").contains(suffix)
                ||sentinel != suffix.equals("sentinel")||resultFixture != suffix.equals("result")
                ||replyPackage==null||replyPackage.isEmpty()){finish();return;}
        if(saved!=null)clicks=saved.getInt("clicks");
        View canvas=new View(this){
            private final Paint marker=new Paint();
            @Override protected void onDraw(Canvas target){super.onDraw(target);marker.setColor((clicks&1)==0?0xffeeeeee:0xff111111);target.drawRect(4,4,12,12,marker);}
        };canvas.setBackgroundColor(0xff000000|getIntent().getIntExtra("fixture_color",0x147c62));
        canvas.setOnTouchListener((view,event)->{if(event.getActionMasked()==MotionEvent.ACTION_UP){
            clicks++;tapFocused=hasWindowFocus();tapDisplay=getDisplay()==null?-1:getDisplay().getDisplayId();tapTask=getTaskId();
            view.invalidate();view.performClick();reply(-1);
            if(resultFixture){setResult(RESULT_OK,new Intent().putExtra(TOKEN,token).putExtra(OWNER_NONCE,ownerNonce)
                    .putExtra("fixture_clicks",clicks).putExtra("fixture_display_id",tapDisplay).putExtra("fixture_task_id",tapTask));finish();}
        }return true;});
        if(sentinel){
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            canvas.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    |View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        canvas.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->reply(-1));
        setContentView(canvas);registerControls();registered=true;canvas.post(()->reply(-1));
    }
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerControls(){
        if(Build.VERSION.SDK_INT>=33)getApplicationContext().registerReceiver(controls,new IntentFilter(CONTROL),Context.RECEIVER_EXPORTED);
        else getApplicationContext().registerReceiver(controls,new IntentFilter(CONTROL));
    }
    private void reply(long request){
        if(token==null||replyPackage==null)return;
        View decor=getWindow().getDecorView();
        getApplicationContext().sendBroadcast(new Intent(STATE).setPackage(replyPackage).putExtra(TOKEN,token)
            .putExtra(OWNER_NONCE,ownerNonce).putExtra(REQUEST,request).putExtra("task_id",getTaskId()).putExtra("display_id",getDisplay()==null?-1:getDisplay().getDisplayId())
            .putExtra("clicks",clicks).putExtra("focus",hasWindowFocus()).putExtra("attached",decor.isAttachedToWindow())
            .putExtra("shown",decor.isShown()).putExtra("window_width",decor.getWidth()).putExtra("window_height",decor.getHeight())
            .putExtra("sentinel",sentinel).putExtra("result_fixture",resultFixture).putExtra("tap_focus",tapFocused)
            .putExtra("tap_display_id",tapDisplay).putExtra("tap_task_id",tapTask)
            .putExtra("multi_window",isInMultiWindowMode()).putExtra("finishing",isFinishing()));
    }
    @Override protected void onSaveInstanceState(Bundle saved){saved.putInt("clicks",clicks);super.onSaveInstanceState(saved);}
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);reply(-1);}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);reply(-1);}
    @Override public void onDestroy(){if(registered)getApplicationContext().unregisterReceiver(controls);super.onDestroy();}
}
