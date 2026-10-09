package net.fuyumori.stellashell;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

/** Only explicitly opened desktop UI owns this ephemeral task; never exported or persisted. */
public final class DesktopInteractionActivity extends Activity {
    private DesktopBackdrop owner;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        owner=DesktopBackdrop.claim(getIntent().getStringExtra("desktop_owner"),this);
        if(owner==null){finish();return;}
        getWindow().getInsetsController().hide(android.view.WindowInsets.Type.systemBars());
        getWindow().getInsetsController().setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(owner!=null&&owner.activity.interactionKey(event))return true;
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed(){if(owner!=null)owner.activity.onBackPressed();else super.onBackPressed();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(owner!=null)owner.result(request,result,data);}
    void finishOwned(){if(owner!=null&&!isFinishing())finishAndRemoveTask();}
    @Override protected void onDestroy(){if(owner!=null)owner.hostDestroyed(this);super.onDestroy();}
}
