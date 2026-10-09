package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.provider.Settings;
import java.util.*;
import java.lang.ref.WeakReference;

/** Process-scoped runtime registry. Persistent requests are not actual output state. */
final class ShellRuntime {
    static final String STOP="net.fuyumori.stellashell.STOP";
    static final String RESET="net.fuyumori.stellashell.RESET_CONNECTION";
    static final String HANDOFF="net.fuyumori.stellashell.HANDOFF";
    interface Session {
        boolean toggleStart(int display);
        default boolean showStart(int display){return false;}
        int[] navigationBounds(int display);
        void homeVisible(boolean visible);
    }
    static final class Snapshot {
        final boolean running,phoneNavigationReady;
        final int selectedDisplay;
        final long generation;
        Snapshot(boolean running,int display,boolean phoneReady,long generation){
            this.running=running;selectedDisplay=display;phoneNavigationReady=phoneReady;this.generation=generation;
        }
    }
    /** Instance-owned registry: injectable without Android for lifetime/lease contracts. */
    static final class Registry {
        static final class Token {
            final WeakReference<Session> session;final long generation;
            Token(Session session,long generation){this.session=new WeakReference<>(session);this.generation=generation;}
        }
        final class HomeLease implements AutoCloseable {
            private boolean visible,closed;
            void visible(boolean value){if(closed||visible==value)return;visible=value;homeChanged();}
            @Override public void close(){if(closed)return;closed=true;homes.remove(this);homeChanged();}
        }
        private Token current;
        private long generation;
        private Snapshot snapshot=new Snapshot(false,-1,false,0);
        private final Set<Runnable> observers=new HashSet<>();
        private final Set<HomeLease> homes=new HashSet<>();
        Token bind(Session session){
            Token token=new Token(session,++generation);current=token;
            snapshot=new Snapshot(true,-1,false,generation);session.homeVisible(homeVisible());changed();return token;
        }
        boolean current(Token token){return current==token;}
        boolean publish(Token token,int display,boolean phoneReady){
            if(!current(token))return false;
            Snapshot previous=snapshot;
            snapshot=new Snapshot(true,display,phoneReady,token.generation);
            if(previous.selectedDisplay!=display||previous.phoneNavigationReady!=phoneReady)changed();return true;
        }
        boolean close(Token token){
            if(!current(token))return false;
            current=null;snapshot=new Snapshot(false,-1,false,token.generation);changed();return true;
        }
        Snapshot snapshot(){return current!=null&&current.session.get()==null?new Snapshot(false,-1,false,snapshot.generation):snapshot;}
        HomeLease attachHome(){HomeLease lease=new HomeLease();homes.add(lease);return lease;}
        private boolean homeVisible(){for(HomeLease lease:homes)if(lease.visible)return true;return false;}
        private void homeChanged(){Session session=current==null?null:current.session.get();if(session!=null)session.homeVisible(homeVisible());}
        void observe(Runnable observer){observers.add(observer);}
        void remove(Runnable observer){observers.remove(observer);}
        void changed(){for(Runnable observer:new ArrayList<>(observers))observer.run();}
        boolean toggleStart(int display){Session session=current==null?null:current.session.get();return session!=null&&session.toggleStart(display);}
        boolean showStart(int display){Session session=current==null?null:current.session.get();return session!=null&&session.showStart(display);}
        int[] navigationBounds(int display){Session session=current==null?null:current.session.get();int[] value=session==null?null:session.navigationBounds(display);return value==null?null:value.clone();}
    }
    static final class Binding implements AutoCloseable {
        private final Context context;
        private final WeakReference<Session> session;
        private final Registry.Token token;
        private boolean closed;
        private Binding(Context context,Session session){this.context=context.getApplicationContext();this.session=new WeakReference<>(session);token=registry.bind(session);}
        boolean current(){return !closed&&registry.current(token);}
        void publish(int display,boolean phoneReady){
            if(closed||!registry.publish(token,display,phoneReady))return;
            // Legacy diagnostic cache only; never a business-state input.
            Launches.prefs(context).edit().putInt("active_display",display).apply();
        }
        @Override public void close(){
            if(closed)return;closed=true;
            if(binding==this)binding=null;
            if(registry.close(token))Launches.prefs(context).edit().remove("active_display").apply();
        }
    }
    static final class HomeVisibilityLease implements AutoCloseable {
        private final Registry.HomeLease lease;
        HomeVisibilityLease(Registry.HomeLease lease){this.lease=lease;}
        void visible(boolean value){lease.visible(value);}
        @Override public void close(){lease.close();}
    }
    private static final Registry registry=new Registry();
    private static Binding binding;
    private ShellRuntime(){}
    static void initialize(Context c){Launches.prefs(c).edit().remove("active_display").apply();}
    static Snapshot snapshot(){return registry.snapshot();}
    static int selectedDisplay(){return snapshot().selectedDisplay;}
    static boolean running(){return snapshot().running;}
    static boolean phoneNavigationReady(){return snapshot().phoneNavigationReady;}
    static boolean enabled(Context c){return Launches.prefs(c).getBoolean("enabled",false);}
    private static void enabled(Context c,boolean value){Launches.prefs(c).edit().putBoolean("enabled",value).apply();registry.changed();}
    static Binding bind(Context c,Session session){Binding next=new Binding(c,session);binding=next;return next;}
    static HomeVisibilityLease attachHomeSurface(){return new HomeVisibilityLease(registry.attachHome());}
    static void observeNavigation(Runnable observer){registry.observe(observer);}
    static void unobserveNavigation(Runnable observer){registry.remove(observer);}
    static boolean toggleStart(int display){return registry.toggleStart(display);}
    static boolean showStart(int display){return registry.showStart(display);}
    static int[] navigationBounds(int display){return registry.navigationBounds(display);}
    static void enableHome(Context c,boolean value){
        c.getPackageManager().setComponentEnabledSetting(new ComponentName(c,DesktopActivity.class),value?PackageManager.COMPONENT_ENABLED_STATE_ENABLED:PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP);
    }
    static void start(Context c){
        int target=c instanceof Activity&&c.getDisplay()!=null&&c.getDisplay().getDisplayId()>0?c.getDisplay().getDisplayId():ShellSettings.of(c).snapshot().preferredDisplayId;
        start(c,target);
    }
    static void start(Context c,int target){start(c,target,true);}
    static void start(Context c,int target,boolean showHome){
        if(!Settings.canDrawOverlays(c)){Ui.message(c,c.getString(R.string.ui_allow_the_taskbar_overlay_first));return;}
        ShellSettings.of(c).setPreferredDisplay(target);enableHome(c,true);enabled(c,true);
        try{c.startForegroundService(new Intent(c,DockService.class).putExtra("show_home",showHome));}
        catch(RuntimeException e){enabled(c,false);enableHome(c,false);Launches.problem(c,e.getMessage());}
    }
    static void handoff(Context c,int display){
        if(!TaskState.of(c).enabled(c)||!enabled(c))return;
        c.startService(new Intent(c,DockService.class).setAction(HANDOFF).putExtra("destination",display));
    }
    static void resetConnection(Context c){
        if(!enabled(c)){ShellSettings.of(c).clearPreferredDisplay();return;}
        try{c.startService(new Intent(c,DockService.class).setAction(RESET));}
        catch(RuntimeException e){Launches.problem(c,e.getMessage());}
    }
    static void stop(Context c){stop(c,true);}
    static void stop(Context c,boolean showHome){
        boolean returnHome=showHome&&Displays.primaryActive(c);enabled(c,false);
        Bridge.get(c).mouseDisplay(-1);
        Bridge.get(c).call(s->{s.setPrimaryMode(false);return "OK";},(result,error)->{});
        if(returnHome)try{c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());}
        catch(RuntimeException e){Launches.problem(c,e.getMessage());}
        c.stopService(new Intent(c,DockService.class));enableHome(c,false);TaskState.of(c).reset(c);
    }
}
