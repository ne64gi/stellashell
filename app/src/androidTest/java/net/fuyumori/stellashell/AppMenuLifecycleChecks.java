package net.fuyumori.stellashell;

import net.fuyumori.stellashell.feature.search.WebSearchSettings;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/** Start-window ownership and generation checks on an owned, disposable display. */
final class AppMenuLifecycleChecks {
    private final Instrumentation test;
    private final Context actual;
    private final String nonce=UUID.randomUUID().toString();
    private final String prefix="app_menu_lifecycle_"+nonce+"_";
    private final Set<String> preferenceFiles=new HashSet<>();
    private final HeldExecutor loader=new HeldExecutor();
    private FixtureContext sandbox;
    private VirtualDisplay display;
    private ImageReader reader;
    private Context windowContext;
    private WindowManager windows;
    private AppMenu menu;
    private int displayId=-1;
    private SharedPreferences production;
    private Map<String,Object> productionBefore;
    private boolean workspaceAutoPresent,workspaceAuto,suppressionAttempted,runtimeRunningBefore;
    private ShellRuntime.Snapshot runtimeBefore;
    private android.content.Intent capturedWebIntent;
    private Bundle capturedWebOptions;
    private int webLaunchAttempts;
    private boolean rejectWebLaunch;

    AppMenuLifecycleChecks(Instrumentation test){this.test=test;actual=test.getTargetContext();}

    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static Field field(Class<?> type,String name){
        try{Field result=type.getDeclaredField(name);result.setAccessible(true);return result;}
        catch(ReflectiveOperationException error){throw new AssertionError("Cannot inspect Start lifecycle state",error);}
    }
    private static Object value(Object target,String name){return get(field(target.getClass(),name),target);}
    private static Object get(Field field,Object target){
        try{return field.get(target);}catch(IllegalAccessException error){throw new AssertionError(error);}
    }
    private static boolean flag(Object target,String name){
        try{return field(target.getClass(),name).getBoolean(target);}catch(IllegalAccessException error){throw new AssertionError(error);}
    }
    private static void invokeRender(AppMenu target){
        try{Method render=AppMenu.class.getDeclaredMethod("render");render.setAccessible(true);render.invoke(target);}
        catch(ReflectiveOperationException error){throw new AssertionError("Could not request a queued Start render",error);}
    }
    private void main(Runnable action){
        Throwable[] failure={null};
        test.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    /** Cross actual UI frames without requiring the entire live shell to stop scheduling work. */
    private void settleUi(){
        java.util.concurrent.CountDownLatch frames=new java.util.concurrent.CountDownLatch(1);
        main(()->android.view.Choreographer.getInstance().postFrameCallback(first->
                android.view.Choreographer.getInstance().postFrameCallback(second->frames.countDown())));
        try{check(frames.await(8,TimeUnit.SECONDS),"UI frame barrier timed out");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
    }
    private static Map<String,Object> persistentPreferences(Map<String,?> source){
        Map<String,Object> result=new HashMap<>();result.putAll(source);
        // Runtime caches are not user settings. Selection is independently checked against ShellRuntime.
        for(String key:new String[]{"active_display","mouse_diagnostics","ime_diagnostics","task_diagnostics",
                "work_area_diagnostics","last_error","icons_revision"})result.remove(key);
        return result;
    }
    private void suppressAutoHandoffForOwnedDisplay(){
        production=Launches.prefs(actual);
        productionBefore=persistentPreferences(production.getAll());
        workspaceAutoPresent=production.contains("workspace_auto");workspaceAuto=production.getBoolean("workspace_auto",false);
        runtimeBefore=ShellFixtureAccess.runtimeSnapshot();
        runtimeRunningBefore=runtimeBefore.running;
        suppressionAttempted=true;
        main(()->check(production.edit().putBoolean("workspace_auto",false).commit(),"Could not temporarily suppress workspace auto-handoff"));
        settleUi();
        Map<String,Object> expected=new HashMap<>(productionBefore);expected.put("workspace_auto",false);
        check(persistentPreferences(production.getAll()).equals(expected),"Temporary auto-handoff suppression changed another persistent preference");
    }

    void run()throws Exception{
        Throwable failure=null;
        try{
            check(Settings.canDrawOverlays(actual),"An existing overlay grant is required; fixture does not change permissions");
            suppressAutoHandoffForOwnedDisplay();
            main(this::createOwnedWindow);
            checkOutputLeaseLifecycle();
            main(()->menu.open(loader));
            View firstRoot=openedRoot();
            EditText firstSearch=(EditText)mainValue("search");
            @SuppressWarnings("unchecked") List<Launches.App> catalog=(List<Launches.App>)mainValue("all");
            check(loader.size()==1,"Initial Start open did not queue exactly one catalog load");

            closeAndCheck(firstRoot,catalog,"Initial close");
            main(()->menu.open(loader));
            View secondRoot=openedRoot();
            check(secondRoot!=firstRoot&&loader.size()==2,"Reopen did not create a new menu generation and catalog request");

            // Complete the closed generation first. It must not publish into the reopened window.
            loader.runNext();settleUi();
            main(()->{
                check(value(menu,"root")==secondRoot&&!flag(menu,"loaded"),"A stale catalog reply marked the reopened menu loaded");
                check(catalog.isEmpty(),"A stale catalog reply repopulated the cleared catalog");
            });
            loader.runNext();settleUi();
            main(()->{
                check(value(menu,"root")==secondRoot&&flag(menu,"loaded"),"The current catalog reply was not accepted");
                check(value(menu,"content") instanceof LinearLayout,"Fresh catalog load did not render the current content view");
                check(!catalog.isEmpty(),"Device has no launcher entries for the catalog-release assertion");
            });

            // Queue a scroll restoration in this generation, then close/reopen before it can run.
            ScrollView retiredScroll=(ScrollView)mainValue("scroll");
            View thirdRoot;
            final int[] lateScrollChanges={0};
            final ScrollView[] freshScroll={null};
            final int[] freshScrollChanges={0};
            main(()->{
                LinearLayout content=(LinearLayout)value(menu,"content");
                content.addView(new View(windowContext),new LinearLayout.LayoutParams(-1,2400));
            });
            settleUi();
            main(()->check(retiredScroll.getChildAt(0).getHeight()>retiredScroll.getHeight(),"Fixture could not establish a real scroll range"));
            main(()->{
                retiredScroll.scrollTo(0,0);
                retiredScroll.setOnScrollChangeListener((view,x,y,oldX,oldY)->lateScrollChanges[0]++);
                invokeRender(menu); // Captures y=0 and posts its restoration to this ScrollView.
                LinearLayout content=(LinearLayout)value(menu,"content");
                content.addView(new View(windowContext),new LinearLayout.LayoutParams(-1,2400));
                retiredScroll.scrollTo(0,31);
                check(retiredScroll.getScrollY()==31,"Could not distinguish the queued scroll restoration from the current position");
                lateScrollChanges[0]=0;
                menu.close();menu.open(loader);
                freshScroll[0]=(ScrollView)value(menu,"scroll");
                LinearLayout freshContent=(LinearLayout)value(menu,"content");
                freshContent.addView(new View(windowContext),new LinearLayout.LayoutParams(-1,2400));
                int width=500,height=300;
                freshScroll[0].measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
                freshScroll[0].layout(0,0,width,height);
                check(freshScroll[0].getChildAt(0).getHeight()>freshScroll[0].getHeight(),"Fresh scroller synthetic content did not lay out with a scroll range");
                freshScroll[0].setOnScrollChangeListener((view,x,y,oldX,oldY)->freshScrollChanges[0]++);
                freshScroll[0].scrollTo(0,93);
                check(freshScroll[0].getScrollY()==93,"Could not establish the fresh menu's nonzero scroll position");
                freshScrollChanges[0]=0;
            });
            thirdRoot=openedRoot();
            check(thirdRoot!=secondRoot&&loader.size()==1,"Close/reopen did not retire the queued-render generation");
            settleUi();
            main(()->{
                check(lateScrollChanges[0]==0&&retiredScroll.getScrollY()==31,"A retired render callback touched its detached ScrollView");
                check(freshScrollChanges[0]==0&&freshScroll[0].getScrollY()==93,"A retired render callback changed the reopened menu's scroll position");
                check(value(menu,"scroll")!=retiredScroll&&value(menu,"root")==thirdRoot,"Reopened menu retained the retired ScrollView");
            });
            loader.runNext();settleUi();
            main(()->check(flag(menu,"loaded"),"Fresh load after queued-render close/reopen failed"));

            // A TextWatcher on the old EditText must not rerender/reset the current menu.
            ScrollView currentScroll=(ScrollView)mainValue("scroll");
            EditText currentSearch=(EditText)mainValue("search");
            main(()->currentSearch.setText("lifecycle-current-query"));
            settleUi();
            main(()->{
                LinearLayout content=(LinearLayout)value(menu,"content");
                content.addView(new View(windowContext),new LinearLayout.LayoutParams(-1,2400));
            });
            settleUi();
            main(()->{
                currentScroll.scrollTo(0,72);
                check(currentScroll.getScrollY()==72,"Fixture could not establish a nonzero current scroll position");
                firstSearch.setText("lifecycle-delayed-old-editor-event");
                check("lifecycle-current-query".contentEquals(currentSearch.getText()),"Old EditText changed the current search text");
                check(currentScroll.getScrollY()==72,"Old EditText event reset the reopened menu scroll position");
            });

            // Repeated closes must release every owner-held view/catalog reference; finish detached.
            closeAndCheck(thirdRoot,catalog,"Lifecycle close after delayed events");
            View lastRoot=thirdRoot;
            for(int cycle=0;cycle<3;cycle++){
                main(()->menu.open(loader));
                View cycleRoot=openedRoot();
                lastRoot=cycleRoot;
                check(loader.size()==1,"Repeated reopen did not queue a fresh catalog request");
                loader.runNext();settleUi();
                main(()->check(flag(menu,"loaded"),"Repeated reopen did not accept its fresh catalog reply"));
                closeAndCheck(cycleRoot,catalog,"Repeated close "+cycle);
            }
            check(firstRoot.getParent()==null&&!firstRoot.isAttachedToWindow(),"First menu root remained attached after repeated close/reopen");
            check(secondRoot.getParent()==null&&!secondRoot.isAttachedToWindow(),"Second menu root remained attached after repeated close/reopen");
            check(lastRoot.getParent()==null&&!lastRoot.isAttachedToWindow(),"Last repeated menu root remained attached after close");
            checkWebSearch();
        }catch(Throwable error){failure=error;}
        finally{
            try{cleanup();}catch(Throwable cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
        }
        if(failure instanceof Exception)throw (Exception)failure;
        if(failure instanceof Error)throw (Error)failure;
        if(failure!=null)throw new AssertionError(failure);
    }

    private Object mainValue(String name){
        Object[] result={null};main(()->result[0]=value(menu,name));return result[0];
    }
    private View openedRoot(){
        settleUi();
        View root=(View)mainValue("root");
        check(root!=null&&root.isAttachedToWindow()&&root.getWidth()>0&&root.getHeight()>0&&root.getDisplay()!=null&&root.getDisplay().getDisplayId()==displayId,"Start did not attach as a real window on the owned display");
        return root;
    }
    private void closeAndCheck(View root,List<Launches.App> catalog,String phase){
        main(()->{
            menu.close();
            check(!menu.isOpen(),phase+" left Start logically open");
            for(String name:new String[]{"root","folderLayer","main","content","scroll","search","webSearchAction","webSearchSession","folderAnchor"})
                check(value(menu,name)==null,phase+" retained the "+name+" view reference");
            check(catalog.isEmpty(),phase+" retained catalog entries");
            check(!flag(menu,"loaded"),phase+" retained its loaded state");
        });
        settleUi();
        check(root.getParent()==null&&!root.isAttachedToWindow(),phase+" root did not detach from WindowManager");
    }

    /** Actual Start Views, but intercept browser intents: never send the fixture query to a server. */
    private void checkWebSearch(){
        WebSearchSettings settings=WebSearchSettings.of(windowContext);
        String query="Fixture & 日本 🚀";
        main(()->menu.open(loader));openedRoot();
        EditText editor=(EditText)mainValue("search");android.widget.Button action=(android.widget.Button)mainValue("webSearchAction");
        main(()->{
            editor.setText(query);
            check(action.getVisibility()==View.GONE&&webLaunchAttempts==0,"Default-off or typing dispatched a Web search");
            settings.save(WebSearchSettings.Provider.CUSTOM,"Fixture engine","https://example.invalid/search?q={query}");
        });settleUi();
        main(()->{
            check(action.getVisibility()==View.VISIBLE&&action.getText().toString().contains("Fixture engine"),"Configured search did not appear while catalog was loading");
            check(query.contentEquals(editor.getText())&&webLaunchAttempts==0,"Settings update changed text or started a browser");
            @SuppressWarnings("unchecked") List<Launches.App> apps=(List<Launches.App>)value(menu,"all");
            apps.add(new Launches.App("net.fuyumori.fixture/.WebApp",query+" app",new android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE)));
            try{field(AppMenu.class,"loaded").setBoolean(menu,true);}catch(IllegalAccessException error){throw new AssertionError(error);}
            invokeRender(menu);
            check(hasText((View)value(menu,"content"),query+" app")&&action.getVisibility()==View.VISIBLE,"Web action replaced matching apps");
            rejectWebLaunch=true;action.performClick();
            check(menu.isOpen()&&query.contentEquals(editor.getText())&&webLaunchAttempts==1,"Failed browser launch closed Start or lost its query");
            rejectWebLaunch=false;action.performClick();
            check(!menu.isOpen()&&webLaunchAttempts==2,"Explicit Web action did not launch once and close Start");
            check(android.content.Intent.ACTION_VIEW.equals(capturedWebIntent.getAction())
                            &&capturedWebIntent.hasCategory(android.content.Intent.CATEGORY_BROWSABLE)
                            &&(capturedWebIntent.getFlags()&android.content.Intent.FLAG_ACTIVITY_NEW_TASK)!=0,
                    "Browser intent changed Android routing semantics");
            check(query.equals(capturedWebIntent.getData().getQueryParameter("q"))
                            &&capturedWebIntent.getData().getFragment()==null,
                    "Reserved/unicode query was not encoded as one search parameter");
            check(capturedWebOptions.getInt("android.activity.launchDisplayId",-1)==displayId,"Web search targeted another display");
            check(value(menu,"webSearchAction")==null&&value(menu,"webSearchSession")==null,"Closed Start retained search View/subscription");
            menu.open(loader);
        });openedRoot();
        EditText freshEditor=(EditText)mainValue("search");android.widget.Button freshAction=(android.widget.Button)mainValue("webSearchAction");
        main(()->{
            freshEditor.setText("new fixture");action.performClick();
            check(webLaunchAttempts==2&&menu.isOpen(),"Retired search button affected the new window");
            settings.save(WebSearchSettings.Provider.NONE,"Fixture engine","https://example.invalid/search?q={query}");
        });settleUi();
        main(()->{
            check(freshAction.getVisibility()==View.GONE&&"new fixture".contentEquals(freshEditor.getText()),"Disabling Web search removed the app query or left a spacer");
            settings.save(WebSearchSettings.Provider.GOOGLE,"Fixture engine","https://example.invalid/search?q={query}");
        });settleUi();
        main(()->{
            freshAction.requestFocus();freshAction.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_ESCAPE));
            check(!menu.isOpen()&&webLaunchAttempts==2,"Escape on the search action did not close Start without launching");
        });settleUi();
    }
    private static boolean hasText(View view,String text){
        if(view instanceof android.widget.TextView&&text.contentEquals(((android.widget.TextView)view).getText()))return true;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(hasText(group.getChildAt(i),text))return true;}
        return false;
    }

    private void createOwnedWindow(){
        reader=ImageReader.newInstance(1200,900,PixelFormat.RGBA_8888,2);
        reader.setOnImageAvailableListener(source->{
            try(Image ignored=source.acquireLatestImage()){ /* Drain fixture frames; never inspect or persist them. */ }
            catch(IllegalStateException closed){ /* A queued image callback can outlive cleanup. */ }
        },new Handler(Looper.getMainLooper()));
        display=actual.getSystemService(DisplayManager.class).createVirtualDisplay(
                "StellaShell app-menu lifecycle "+nonce,1200,900,160,reader.getSurface(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        check(display!=null,"Could not create the fixture-owned public display");
        Display owned=display.getDisplay();displayId=owned.getDisplayId();
        check(displayId>0&&owned.isValid()&&(owned.getFlags()&Display.FLAG_PRIVATE)==0,"Lifecycle fixture must use its own public non-primary display");
        sandbox=new FixtureContext(actual);
        windowContext=sandbox.createDisplayContext(owned).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
        windows=windowContext.getSystemService(WindowManager.class);
        menu=new AppMenu(windowContext,windows,displayId);
    }

    /** A retired task-output lease cannot close or publish into its replacement. */
    private void checkOutputLeaseLifecycle(){
        TaskState tasks=TaskState.of(sandbox);
        Context outputContext=sandbox.createDisplayContext(display.getDisplay());
        main(()->{
            TaskState.OutputLease oldLease=tasks.openOutput(outputContext,displayId,()->{},()->true);
            TaskSession oldSession=ShellFixtureAccess.taskSession(tasks);
            check(oldSession!=null&&oldSession.displayId()==displayId,"First task-output lease did not attach to the owned display");
            long oldEpoch=oldSession.outputEpoch();

            TaskState.OutputLease currentLease=tasks.openOutput(outputContext,displayId,()->{},()->true);
            TaskSession currentSession=ShellFixtureAccess.taskSession(tasks);
            TaskSnapshot currentSnapshot=tasks.snapshot();
            long currentEpoch=currentSession.outputEpoch();
            check(currentSession!=oldSession&&currentEpoch>oldEpoch
                            &&currentSnapshot.outputEpoch==currentEpoch&&currentSnapshot.displayId==displayId,
                    "Replacement output did not establish a fresh display epoch");

            // openOutput posted a poll callback, but this entire transition runs
            // in one main-loop action; both leases close before that callback can
            // reach the real task backend.
            oldLease.close();oldLease.close();
            check(tasks.snapshot()==currentSnapshot&&tasks.snapshot().outputEpoch==currentEpoch
                            &&tasks.snapshot().displayId==displayId,
                    "Closing a stale output lease changed the current output");

            TaskSnapshot.Task lateTask=new TaskSnapshot.Task(91009,
                    "net.fuyumori.fixture/.LeaseProbe",5,true,false,false,1,2,301,402);
            TaskSnapshot lateReply=new TaskSnapshot(999,oldEpoch,displayId,
                    Collections.singletonList(lateTask),Collections.singletonList(lateTask),
                    false,false,false);
            tasks.publish(oldSession,lateReply);
            check(tasks.snapshot()==currentSnapshot&&tasks.snapshot().tasks.isEmpty(),
                    "A stale task reply replaced the current snapshot");

            currentLease.close();
            TaskSnapshot closedSnapshot=tasks.snapshot();
            check(closedSnapshot.displayId==-1&&closedSnapshot.tasks.isEmpty(),
                    "Closing the current output did not publish the empty detached state");
            currentLease.close();
            check(tasks.snapshot()==closedSnapshot&&tasks.snapshot().outputEpoch==closedSnapshot.outputEpoch
                            &&tasks.snapshot().displayId==-1,
                    "Closing the current output twice was not idempotent");
        });
    }

    private void cleanup(){
        Throwable[] failure={null};
        attempt(failure,()->{if(menu!=null)main(menu::close);});
        attempt(failure,loader::shutdownNow);
        attempt(failure,this::settleUi);
        attempt(failure,()->{if(sandbox!=null)sandbox.closeOwners();});
        // Failure-path checks display a real LENGTH_LONG toast. Its SystemUI
        // animation must finish before removing the display that owns it.
        if(webLaunchAttempts>0){
            android.view.accessibility.AccessibilityManager access=actual.getSystemService(android.view.accessibility.AccessibilityManager.class);
            int toastMillis=access==null?3500:access.getRecommendedTimeoutMillis(3500,android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT);
            android.os.SystemClock.sleep(Math.max(3500,toastMillis)+1500L);
        }
        attempt(failure,()->{if(display!=null){main(()->{display.release();display=null;});}});
        attempt(failure,()->{if(reader!=null){main(()->{reader.close();reader=null;});}});
        attempt(failure,()->{if(displayId>0)WorkArea.remove(displayId);});
        attempt(failure,()->{for(String name:preferenceFiles)actual.deleteSharedPreferences(name);});
        attempt(failure,this::settleUi);
        if(suppressionAttempted)attempt(failure,this::restoreAndVerifyProductionState);
        if(failure[0]!=null)throw new AssertionError("AppMenu lifecycle fixture cleanup failed",failure[0]);
    }
    private static void attempt(Throwable[] failure,Runnable action){
        try{action.run();}catch(Throwable error){if(failure[0]==null)failure[0]=error;else failure[0].addSuppressed(error);}
    }
    private void restoreAndVerifyProductionState(){
        main(()->{
            SharedPreferences.Editor restore=production.edit();
            if(workspaceAutoPresent)restore.putBoolean("workspace_auto",workspaceAuto);else restore.remove("workspace_auto");
            check(restore.commit(),"Could not restore workspace_auto presence/value");
        });
        settleUi();
        check(production.contains("workspace_auto")==workspaceAutoPresent&&production.getBoolean("workspace_auto",false)==workspaceAuto,
                "workspace_auto was not restored exactly");
        check(persistentPreferences(production.getAll()).equals(productionBefore),"Persistent preferences changed during the fixture");
        ShellRuntime.Snapshot after=ShellFixtureAccess.runtimeSnapshot();
        check(after.generation==runtimeBefore.generation&&after.running==runtimeRunningBefore
                        &&after.selectedDisplay==runtimeBefore.selectedDisplay,
                "Fixture changed the live ShellRuntime generation/output state");
    }

    private static final class HeldExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> queued=new ArrayDeque<>();
        private boolean stopped;
        @Override public synchronized void execute(Runnable task){if(stopped)throw new java.util.concurrent.RejectedExecutionException();queued.addLast(task);}
        synchronized int size(){return queued.size();}
        void runNext(){
            Runnable task;
            synchronized(this){task=queued.pollFirst();}
            check(task!=null,"No controlled catalog task was queued");
            task.run();
        }
        @Override public synchronized void shutdown(){stopped=true;}
        @Override public synchronized List<Runnable> shutdownNow(){stopped=true;List<Runnable> pending=new java.util.ArrayList<>(queued);queued.clear();return pending;}
        @Override public synchronized boolean isShutdown(){return stopped;}
        @Override public synchronized boolean isTerminated(){return stopped&&queued.isEmpty();}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
    }

    private final class FixtureOwners {
        private ShellSettings settings;
        private TaskState tasks;
        synchronized ShellSettings settings(SharedPreferences preferences){
            if(settings==null)settings=ShellSettings.isolated(preferences);
            return settings;
        }
        synchronized TaskState tasks(Context context){
            if(tasks==null)tasks=TaskState.isolated(context);
            return tasks;
        }
        synchronized void close(){
            if(tasks!=null){tasks.closeOwner();tasks=null;}
            if(settings!=null){settings.close();settings=null;}
        }
    }

    private final class FixtureContext extends ContextWrapper implements ShellSettings.Provider,TaskState.Provider {
        private final FixtureOwners owners;
        FixtureContext(Context base){this(base,new FixtureOwners());}
        private FixtureContext(Context base,FixtureOwners owners){super(base);this.owners=owners;}
        @Override public Context getApplicationContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            String isolated=prefix+name;preferenceFiles.add(isolated);return actual.getSharedPreferences(isolated,mode);
        }
        @Override public ShellSettings shellSettings(){return owners.settings(getSharedPreferences("desktop",Context.MODE_PRIVATE));}
        @Override public TaskState taskState(){return owners.tasks(this);}
        @Override public Context createDisplayContext(Display target){return new FixtureContext(super.createDisplayContext(target),owners);}
        @Override public Context createWindowContext(int type,Bundle options){return new FixtureContext(super.createWindowContext(type,options),owners);}
        @Override public void startActivity(android.content.Intent intent,Bundle options){
            if(android.content.Intent.ACTION_VIEW.equals(intent.getAction())){
                webLaunchAttempts++;capturedWebIntent=new android.content.Intent(intent);capturedWebOptions=new Bundle(options);
                if(rejectWebLaunch)throw new android.content.ActivityNotFoundException("Fixture browser unavailable");
                return; // Capture only: never launch a real browser or perform a network query.
            }
            super.startActivity(intent,options);
        }
        void closeOwners(){owners.close();}
    }
}
