package net.fuyumori.stellashell;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;

/** Transparent ALL_APPS entry: opening Start must not raise an opaque desktop task. */
public final class StartMenuActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        Intent request=getIntent();
        if(request.getBooleanExtra(StartMenuRequests.SYSTEM_REQUEST,false)&&!StartMenuRequests.defaultHome(this)){
            android.content.ComponentName home=StartMenuRequests.home(this);
            if(home!=null&&!getPackageName().equals(home.getPackageName()))startActivity(new Intent(Intent.ACTION_ALL_APPS)
                    .setComponent(home).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();return;
        }
        if(StartMenuRequests.operation(request.getAction())!=StartMenuRequests.Operation.NONE
                &&!StartMenuRequests.route(request)
                &&StartMenuRequests.target(request.getIntExtra(StartMenuRequests.DISPLAY,-1),ShellRuntime.selectedDisplay())==0){
            startActivity(new Intent(this,HomeActivity.class).setAction(request.getAction())
                    .putExtra(StartMenuRequests.DISPLAY,0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
        }
        finish();
    }
}
