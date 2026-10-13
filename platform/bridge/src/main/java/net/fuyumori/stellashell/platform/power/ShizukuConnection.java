package net.fuyumori.stellashell.platform.power;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import net.fuyumori.stellashell.IDesktopBridge;
import rikka.shizuku.Shizuku;

/** SDK-only transport. It knows nothing about shell preferences, leases, or base runtime. */
public final class ShizukuConnection {
    public static final int SERVICE_VERSION=41;
    public enum Status { NOT_RUNNING, OLD_API, PERMISSION, WAITING, READY, ERROR }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Shizuku.UserServiceArgs args;
    private final Runnable changed;
    private volatile IDesktopBridge service;
    private volatile boolean wanted;
    private volatile boolean binding;
    private volatile long epoch;
    private ServiceConnection connection;
    private volatile String error="";
    public ShizukuConnection(Context context,ComponentName entry,Runnable changed){
        this.changed=changed;
        args=new Shizuku.UserServiceArgs(entry).daemon(false).processNameSuffix("desktop_bridge")
                .debuggable(false).version(SERVICE_VERSION);
        Shizuku.addBinderReceivedListenerSticky(()->main.post(()->{if(wanted)connect();signal();}));
        Shizuku.addBinderDeadListener(()->main.post(()->{epoch++;service=null;binding=false;signal();}));
        Shizuku.addRequestPermissionResultListener((code,result)->main.post(()->{
            if(code!=401)return;
            if(wanted&&result==PackageManager.PERMISSION_GRANTED)connect();signal();
        }));
    }
    public IDesktopBridge service(){
        IDesktopBridge current=service;
        return current!=null&&current.asBinder().isBinderAlive()?current:null;
    }
    public boolean ready(){return service()!=null;}
    public long epoch(){return epoch;}
    public boolean authorized(){
        try{return Shizuku.pingBinder()&&Shizuku.getVersion()>=13&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}
        catch(RuntimeException failure){return false;}
    }
    public Status status(){
        try{
            if(!Shizuku.pingBinder())return Status.NOT_RUNNING;
            if(Shizuku.getVersion()<13)return Status.OLD_API;
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)return Status.PERMISSION;
            return ready()?Status.READY:error.isEmpty()?Status.WAITING:Status.ERROR;
        }catch(RuntimeException failure){return Status.ERROR;}
    }
    public String error(){return error;}
    /** False blocks binds/permission requests immediately; cleanup may still use the live service. */
    public void wanted(boolean value){wanted=value;if(value)connect();signal();}
    public void request(){
        if(!wanted)return;
        try{
            if(!Shizuku.pingBinder()){signal();return;}
            if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED)connect();
            else Shizuku.requestPermission(401);
        }catch(RuntimeException failure){error=message(failure);signal();}
    }
    public void connect(){
        if(!wanted)return;
        bind();
    }
    /** Explicit cleanup retry may reconnect OFF, but never asks for permission or runs work. */
    public void connectForCleanup(){bind();}
    private void bind(){
        if(binding||ready()||!authorized())return;
        long token=++epoch;binding=true;error="";
        ServiceConnection previous=connection;
        if(previous!=null){
            // Drop only the stale client connection, never destroy a server with cleanup debt.
            try{Shizuku.unbindUserService(args,previous,false);}
            catch(RuntimeException failure){error=message(failure);}
        }
        ServiceConnection next=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder binder){
                if(epoch!=token){
                    // A timed-out/replaced bind can complete late. Detach its client only;
                    // never publish it or destroy a server another session may still own.
                    try{Shizuku.unbindUserService(args,this,false);}
                    catch(RuntimeException ignored){}
                    return;
                }
                service=IDesktopBridge.Stub.asInterface(binder);binding=false;error="";signal();
            }
            @Override public void onServiceDisconnected(ComponentName name){
                if(epoch!=token)return;
                epoch++;service=null;binding=false;signal();
            }
        };
        connection=next;
        try{
            Shizuku.bindUserService(args,next);
            main.postDelayed(()->{
                if(epoch!=token||!binding||ready())return;
                epoch++;binding=false;error="Connection timed out";signal();
            },6000);
        }catch(RuntimeException failure){epoch++;binding=false;error=message(failure);signal();}
    }
    /** Called only after the remote cleanup contract has succeeded, never while debt remains. */
    public void disconnect(){
        wanted=false;epoch++;service=null;binding=false;
        ServiceConnection previous=connection;connection=null;
        if(previous!=null){
            try{Shizuku.unbindUserService(args,previous,true);}
            catch(RuntimeException failure){error=message(failure);}
        }
        signal();
    }
    public boolean binding(){return binding;}
    public boolean hasConnection(){return connection!=null;}
    private void signal(){changed.run();}
    private static String message(Exception failure){return failure.getMessage()==null?failure.toString():failure.getMessage();}
}
