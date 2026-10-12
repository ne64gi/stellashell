package net.fuyumori.stellashell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/** Main-thread, resumed-surface subscriptions. No periodic or failure-retry timer. */
final class QuickSettingsEvents implements AutoCloseable {
    static final int NETWORK=1, AUDIO=2, SCREEN=4, LIFETIME=8;
    static final int ALL=NETWORK|AUDIO|SCREEN|LIFETIME;
    interface Listener { void changed(int fields); }
    interface Backend {
        void start(Listener listener);
        void stop(Listener listener);
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Backend backend;
    private final Listener refresh;
    private Listener subscription;
    private long generation;
    private int pending;
    private boolean closed;
    private final Runnable deliver=this::deliver;
    private void deliver(){
        int fields=pending;pending=0;
        if(subscription!=null&&fields!=0)refresh.changed(fields);
    }

    QuickSettingsEvents(Context context,int display,Listener refresh){
        this(new AndroidBackend(context,display),refresh);
    }
    QuickSettingsEvents(Backend backend,Listener refresh){this.backend=backend;this.refresh=refresh;}
    void start(){
        if(closed||subscription!=null)return;
        long epoch=++generation;
        subscription=fields->{if(current(epoch))changed(fields);};
        try{backend.start(subscription);}
        catch(RuntimeException failure){stop();throw failure;}
        changed(ALL);
    }
    void changed(int fields){
        if(subscription==null||closed)return;
        boolean enqueue=pending==0;pending|=fields&ALL;
        if(enqueue&&pending!=0)main.post(deliver);
    }
    long generation(){return generation;}
    boolean current(long epoch){return !closed&&subscription!=null&&generation==epoch;}
    void stop(){
        Listener previous=subscription;subscription=null;++generation;
        pending=0;main.removeCallbacks(deliver);
        if(previous!=null)backend.stop(previous);
    }
    @Override public void close(){stop();closed=true;}

    static final class AndroidBackend implements Backend {
        private final Context context;
        private final int display;
        private final Handler main=new Handler(Looper.getMainLooper());
        private final ConnectivityManager connectivity;
        private final DisplayManager displays;
        private final AudioManager audio;
        private final Bridge bridge;
        private ConnectivityManager.NetworkCallback network;
        private BroadcastReceiver wifi;
        private ContentObserver settings;
        private AudioDeviceCallback devices;
        private DisplayManager.DisplayListener displayListener;
        private Runnable bridgeChanged,runtimeChanged;

        AndroidBackend(Context context,int display){
            this.context=context.getApplicationContext();this.display=display;
            connectivity=context.getSystemService(ConnectivityManager.class);
            displays=context.getSystemService(DisplayManager.class);
            audio=context.getSystemService(AudioManager.class);bridge=Bridge.get(context);
        }
        boolean registered(){
            return connectivity!=null&&displays!=null&&audio!=null&&network!=null&&wifi!=null
                    &&settings!=null&&devices!=null&&displayListener!=null&&bridgeChanged!=null&&runtimeChanged!=null;
        }
        @Override public void start(Listener listener){
            bridgeChanged=()->listener.changed(SCREEN);
            runtimeChanged=()->listener.changed(LIFETIME|SCREEN);
            bridge.observe(bridgeChanged);ShellRuntime.observeNavigation(runtimeChanged);
            displayListener=new DisplayManager.DisplayListener(){
                public void onDisplayAdded(int id){}
                public void onDisplayChanged(int id){}
                public void onDisplayRemoved(int id){if(id==display)listener.changed(LIFETIME);}
            };
            try{if(displays!=null)displays.registerDisplayListener(displayListener,main);}
            catch(RuntimeException ignored){displayListener=null;}
            network=new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network value){listener.changed(NETWORK);}
                @Override public void onLost(Network value){listener.changed(NETWORK);}
            };
            try{
                if(connectivity!=null)connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                        .clearCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),network,main);
            }catch(RuntimeException ignored){network=null;}
            wifi=new BroadcastReceiver(){
                @Override public void onReceive(Context ignored,Intent intent){listener.changed(NETWORK);}
            };
            try{
                IntentFilter filter=new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION);
                if(Build.VERSION.SDK_INT>=33)context.registerReceiver(wifi,filter,Context.RECEIVER_NOT_EXPORTED);
                else context.registerReceiver(wifi,filter);
            }catch(RuntimeException ignored){wifi=null;}
            // Public APIs provide no general STREAM_MUSIC index listener on API 30–35.
            // Observe persisted system-setting changes without private volume key names or
            // hidden broadcasts. OEM persistence timing can delay external volume updates;
            // this Activity's keys/slider refresh directly and every resume rereads audio.
            settings=new ContentObserver(main){
                @Override public void onChange(boolean selfChange){listener.changed(AUDIO);}
            };
            try{context.getContentResolver().registerContentObserver(Settings.System.CONTENT_URI,true,settings);}
            catch(RuntimeException ignored){settings=null;}
            devices=new AudioDeviceCallback(){
                @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added){listener.changed(AUDIO);}
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed){listener.changed(AUDIO);}
            };
            try{if(audio!=null)audio.registerAudioDeviceCallback(devices,main);}
            catch(RuntimeException ignored){devices=null;}
        }
        @Override public void stop(Listener listener){
            bridge.remove(bridgeChanged);ShellRuntime.unobserveNavigation(runtimeChanged);
            if(displays!=null&&displayListener!=null)try{displays.unregisterDisplayListener(displayListener);}catch(RuntimeException ignored){}
            if(connectivity!=null&&network!=null)try{connectivity.unregisterNetworkCallback(network);}catch(RuntimeException ignored){}
            if(wifi!=null)try{context.unregisterReceiver(wifi);}catch(RuntimeException ignored){}
            if(settings!=null)try{context.getContentResolver().unregisterContentObserver(settings);}catch(RuntimeException ignored){}
            if(audio!=null&&devices!=null)try{audio.unregisterAudioDeviceCallback(devices);}catch(RuntimeException ignored){}
            bridgeChanged=null;runtimeChanged=null;displayListener=null;network=null;wifi=null;settings=null;devices=null;
        }
    }
}
