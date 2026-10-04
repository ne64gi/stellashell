package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.tasks.TaskModes;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Synthetic task feeds and captured callbacks only; never queries or focuses personal tasks. */
final class PhoneSidebarChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    /** Root invokes with existing fixture checks on the main thread. */
    static void run(Instrumentation test)throws Exception{
        lifecycle();recovery();operations();fullscreen();pipFullscreen();
        Context base=test.getTargetContext();
        for(Locale locale:new Locale[]{Locale.ENGLISH,Locale.JAPANESE}){
            Configuration config=new Configuration(base.getResources().getConfiguration());config.setLocale(locale);
            Context context=new ContextThemeWrapper(base.createConfigurationContext(config),R.style.AppTheme);
            rendering(context);menus(context);taskbarActions(context);
        }
    }
    private static TaskSnapshot.Task task(int id,String component,boolean focused)throws Exception{
        return task(id,component,focused,1);
    }
    private static TaskSnapshot.Task task(int id,String component,boolean focused,int mode)throws Exception{
        return new TaskSnapshot.Task(new JSONObject().put("id",id).put("component",component).put("mode",mode)
            .put("visible",focused).put("focused",focused).put("left",0).put("top",0).put("right",360).put("bottom",720));
    }
    private static String snapshot(TaskSnapshot.Task...tasks)throws Exception{
        JSONArray rows=new JSONArray();
        for(TaskSnapshot.Task task:tasks)rows.put(new JSONObject().put("id",task.id).put("component",task.component).put("mode",task.mode)
            .put("visible",task.visible).put("focused",task.focused).put("left",0).put("top",0).put("right",360).put("bottom",720));
        return new JSONObject().put("tasks",rows).toString();
    }
    private static final class FakeBackend implements PhoneRunningTasks.Backend {
        boolean ready=true;int observed,removed,requests,connections;Runnable observer;Bridge.Reply snapshot,focus;
        TaskSnapshot.Task focused,operated;int operations;String action;Rect bounds;Bridge.Reply operation;
        public boolean ready(){return ready;}
        public void connect(){connections++;}
        public void observe(Runnable listener){observed++;observer=listener;}
        public void remove(Runnable listener){check(observer==listener,"removed different observer");removed++;observer=null;}
        public void snapshot(Bridge.Reply reply){requests++;check(snapshot==null,"overlapping snapshot request");snapshot=reply;}
        public void focus(TaskSnapshot.Task task,Bridge.Reply reply){focused=task;focus=reply;}
        public void operation(TaskSnapshot.Task task,String action,Rect bounds,Bridge.Reply reply){operations++;operated=task;this.action=action;this.bounds=new Rect(bounds);operation=reply;}
        void answer(String result,String error){Bridge.Reply reply=snapshot;check(reply!=null,"no pending snapshot");snapshot=null;reply.done(result,error);}
    }
    private static void recovery()throws Exception{
        FakeBackend backend=new FakeBackend();backend.ready=false;int[] changes={0};
        PhoneRunningTasks feed=new PhoneRunningTasks(backend,()->changes[0]++);
        try{
            feed.refresh();check(backend.connections==0,"hidden feed attempted reconnect");
            feed.start();feed.refresh();
            check(backend.connections==1&&backend.requests==0&&feed.state()==PhoneRunningTasks.State.UNAVAILABLE,"visible disconnected feed did not reconnect or distinguish unavailable");
            int before=changes[0];feed.refresh();check(changes[0]==before,"unchanged connection state rebuilt sidebar");
            backend.ready=true;backend.observer.run();backend.answer(snapshot(),null);
            check(feed.state()==PhoneRunningTasks.State.READY&&feed.tasks().isEmpty()&&changes[0]>before,"successful empty snapshot still appeared disconnected");
            feed.refresh();backend.answer(null,"synthetic snapshot failure");
            check(feed.state()==PhoneRunningTasks.State.ERROR,"snapshot error appeared as a valid empty list");
            feed.refresh();backend.answer(snapshot(task(801,"fixture.recovery/.Main",true)),null);
            check(feed.state()==PhoneRunningTasks.State.READY&&feed.tasks().size()==1,"snapshot did not recover after failure");
            feed.stop();int connections=backend.connections;backend.ready=false;feed.refresh();
            check(backend.connections==connections,"hidden feed continued reconnecting");
        }finally{feed.close();}
    }
    private static void lifecycle()throws Exception{
        FakeBackend backend=new FakeBackend();int[] changes={0},focused={0},failures={0};
        PhoneRunningTasks feed=new PhoneRunningTasks(backend,()->changes[0]++);
        TaskSnapshot.Task first=task(401,"fixture.chat/.Main",true),second=task(402,"fixture.chat/.Main",false),other=task(403,"fixture.mail/.Main",false);
        try{
            feed.refresh();check(backend.requests==0,"hidden feed queried tasks");
            feed.start();feed.start();feed.refresh();feed.refresh();
            check(backend.observed==1&&backend.requests==1,"duplicate observe or unbounded in-flight requests");
            backend.answer(snapshot(first,second,other),null);
            check(feed.tasks().size()==3&&changes[0]==1,"synthetic tasks missing or combined by app");
            feed.refresh();backend.answer(snapshot(first,second,other),null);check(changes[0]==1,"unchanged feed rebuilt views");
            TaskSnapshot.Task floating=task(first.id,first.component,first.focused,5);
            feed.refresh();backend.answer(snapshot(floating,second,other),null);check(changes[0]==2&&feed.tasks().get(0).mode==5,"mode-only change did not invalidate cached task menu");
            feed.refresh();backend.answer(snapshot(first,second,other),null);check(changes[0]==3&&feed.tasks().get(0).mode==1,"fullscreen mode-only change did not refresh task menu");
            feed.focus(second,()->focused[0]++,error->failures[0]++);
            check(backend.focused.id==402&&backend.focused.component.equals(second.component),"focus mixed same-app task identity");
            backend.focus.done("OK",null);check(focused[0]==1&&failures[0]==0,"focus callback");
            feed.focus(task(999,"fixture.chat/.Main",false),()->focused[0]++,error->failures[0]++);
            check(backend.focused.id==402,"stale task was focused");
            check(backend.snapshot!=null,"stale task did not refresh feed");
            feed.stop();check(feed.tasks().isEmpty()&&backend.removed==1,"hide retained tasks/observer");
            int before=changes[0];backend.answer(snapshot(first),null);
            check(feed.tasks().isEmpty()&&changes[0]==before,"late hidden snapshot restored stale content");
            feed.start();feed.refresh();feed.stop();feed.start();feed.refresh();
            int requests=backend.requests;backend.answer(snapshot(first),null);
            check(backend.requests==requests+1&&feed.tasks().isEmpty(),"restart accepted old generation or overlapped request");
            backend.answer(snapshot(second),null);check(feed.tasks().size()==1&&feed.tasks().get(0).id==402,"restart failed fresh snapshot");
            feed.focus(second,()->focused[0]++,error->failures[0]++);Bridge.Reply lateFocus=backend.focus;
            feed.stop();lateFocus.done("OK",null);check(focused[0]==1,"late focus callback changed hidden sidebar");
            feed.start();feed.refresh();backend.answer(snapshot(first),null);
            backend.ready=false;backend.observer.run();check(feed.tasks().isEmpty(),"permission/binder loss left stale tasks");
            requests=backend.requests;feed.refresh();check(backend.requests==requests,"unavailable backend queried tasks");
            backend.ready=true;backend.observer.run();backend.answer(snapshot(first),null);
            feed.refresh();backend.answer("invalid synthetic payload",null);check(feed.tasks().isEmpty(),"bad snapshot retained stale tasks");
            feed.refresh();backend.answer(snapshot(first),null);
            feed.focus(first,()->focused[0]++,error->failures[0]++);backend.focus.done(null,"synthetic focus failure");
            check(failures[0]==1&&backend.snapshot!=null,"failed focus missing error/revalidation");
            feed.close();before=changes[0];backend.answer(snapshot(first),null);
            check(feed.tasks().isEmpty()&&changes[0]==before&&backend.observed==backend.removed,"close leaked callback/observer");
            requests=backend.requests;feed.start();feed.refresh();check(backend.requests==requests,"closed feed restarted");
        }finally{feed.close();}
    }
    private static void fullscreen()throws Exception{
        FakeBackend backend=new FakeBackend();int[] completed={0},failed={0},changes={0};
        PhoneRunningTasks feed=new PhoneRunningTasks(backend,()->changes[0]++);
        TaskSnapshot.Task floating=task(601,"fixture.chat/.Main",true,5),main=task(601,"fixture.chat/.Main",true,1),other=task(602,"fixture.mail/.Main",false);
        try{
            feed.start();feed.refresh();backend.answer(snapshot(floating,other),null);
            feed.operation(floating,"fullscreen",null,()->completed[0]++,error->failed[0]++);
            check(backend.operations==1&&backend.operated.id==601&&backend.operated.component.equals(floating.component)&&"fullscreen".equals(backend.action)&&backend.bounds.isEmpty(),"fullscreen lost task identity or forwarded stale floating bounds");
            backend.operation.done("OK",null);check(completed[0]==1&&failed[0]==0,"fullscreen completion");
            feed.refresh();backend.answer(snapshot(main,other),null);check(changes[0]==2&&feed.tasks().get(0).mode==1&&feed.tasks().get(1).id==602,"fullscreen feed did not update same-focus mode or changed unrelated task");
            feed.operation(floating,"fullscreen",null,()->completed[0]++,error->failed[0]++);check(backend.operations==1,"stale fullscreen action executed after mode change");
            backend.answer(snapshot(main,other),null);
            feed.operation(main,"float",new Rect(50,60,300,500),()->completed[0]++,error->failed[0]++);
            check(backend.operations==2&&"float".equals(backend.action),"returned main task cannot become subwindow again");backend.operation.done("OK",null);
            String actual=new JSONObject().put("task",new JSONObject(snapshot(main)).getJSONArray("tasks").getJSONObject(0)).toString();
            check(PhoneRunningTasks.verifiedTask(actual,floating,1).mode==1,"verified fullscreen task response");
            boolean rejected=false;try{PhoneRunningTasks.verifiedTask(actual,floating,5);}catch(Exception expected){rejected=true;}check(rejected,"wrong actual window mode accepted");
            rejected=false;try{PhoneRunningTasks.verifiedTask(actual,task(601,"fixture.other/.Main",true),1);}catch(Exception expected){rejected=true;}check(rejected,"wrong actual component accepted");
            rejected=false;try{PhoneRunningTasks.verifiedTask(actual,task(999,"fixture.chat/.Main",true),1);}catch(Exception expected){rejected=true;}check(rejected,"wrong actual task ID accepted");
        }finally{feed.close();}
    }
    private static void operations()throws Exception{
        FakeBackend backend=new FakeBackend();int[] completed={0},failed={0};
        PhoneRunningTasks feed=new PhoneRunningTasks(backend,()->{});
        TaskSnapshot.Task first=task(501,"fixture.chat/.Main",true),second=task(502,"fixture.chat/.Main",false);
        Rect bounds=PhoneTaskMenu.floatingBounds(new Rect(10,20,910,1220));
        check(bounds.equals(new Rect(160,220,760,1020)),"float bounds differ from existing centered two-thirds rule");
        check(PhoneTaskMenu.floatingBounds(new Rect(10,20,11,21)).equals(new Rect(10,20,11,21)),"tiny content bounds empty/outside");
        try{
            feed.start();feed.refresh();backend.answer(snapshot(first,second),null);
            feed.operation(first,"unsupported",bounds,()->completed[0]++,error->failed[0]++);
            feed.operation(first,"float",new Rect(),()->completed[0]++,error->failed[0]++);
            check(backend.operations==0,"invalid operation/bounds submitted");
            feed.operation(second,"float",bounds,()->completed[0]++,error->failed[0]++);
            check(backend.operations==1&&backend.operated.id==502&&backend.operated.component.equals(second.component)&&"float".equals(backend.action)&&backend.bounds.equals(bounds),"float changed task identity/bounds or relaunched app");
            feed.operation(first,"close",bounds,()->completed[0]++,error->failed[0]++);
            check(backend.operations==1,"duplicate command entered during pending task operation");
            backend.operation.done("OK",null);check(completed[0]==1,"float callback missing");
            feed.operation(first,"close",bounds,()->completed[0]++,error->failed[0]++);
            check(backend.operations==2&&backend.operated.id==501&&"close".equals(backend.action)&&backend.bounds.isEmpty(),"close targeted app rather than task or forwarded irrelevant bounds");
            backend.operation.done(null,"synthetic backend rejection");check(failed[0]==1&&backend.snapshot!=null,"operation error not surfaced/revalidated");
            backend.answer(snapshot(second),null);
            feed.operation(first,"close",bounds,()->completed[0]++,error->failed[0]++);check(backend.operations==2,"removed task action executed");
            backend.answer(snapshot(second),null);
            feed.operation(task(502,"fixture.other/.Main",false),"close",bounds,()->completed[0]++,error->failed[0]++);
            check(backend.operations==2,"same task ID with different component accepted");backend.answer(snapshot(second),null);
            feed.operation(second,"float",bounds,()->completed[0]++,error->failed[0]++);Bridge.Reply late=backend.operation;
            feed.stop();late.done("OK",null);check(completed[0]==1,"hidden late operation callback executed");
            feed.operation(second,"close",bounds,()->completed[0]++,error->failed[0]++);check(backend.operations==3,"hidden task action submitted");
            feed.start();feed.refresh();backend.answer(snapshot(second),null);
            backend.ready=false;feed.operation(second,"close",bounds,()->completed[0]++,error->failed[0]++);
            check(backend.operations==3&&feed.tasks().isEmpty(),"permission loss allowed task action or retained tasks");
        }finally{feed.close();}
    }
    private static void pipFullscreen()throws Exception{
        FakeBackend backend=new FakeBackend();int[] completed={0};PhoneRunningTasks feed=new PhoneRunningTasks(backend,()->{});
        TaskSnapshot.Task pip=task(711,"fixture.video/.Player",true,2),main=task(711,"fixture.video/.Player",true,1),other=task(712,"fixture.mail/.Main",false,1);
        try{
            feed.start();feed.refresh();backend.answer(snapshot(pip,other),null);
            feed.operation(pip,"fullscreen",null,()->completed[0]++,error->{throw new AssertionError(error);});
            check(backend.operations==1&&backend.operated.id==711&&backend.operated.component.equals(pip.component)&&"fullscreen".equals(backend.action)&&backend.bounds.isEmpty(),"PiP return-to-main rejected or lost exact selected task identity");
            backend.operation.done("OK",null);check(completed[0]==1,"PiP fullscreen callback missing");
            feed.refresh();backend.answer(snapshot(main,other),null);
            feed.operation(pip,"fullscreen",null,()->completed[0]++,error->{throw new AssertionError(error);});
            check(backend.operations==1&&completed[0]==1&&backend.snapshot!=null,"Stale PiP return operated an already-main task instead of refreshing");
            backend.answer(snapshot(main,other),null);check(feed.tasks().get(0).mode==1&&feed.tasks().get(1).id==712,"PiP return altered unrelated synthetic task");
        }finally{feed.close();}
    }
    private static void menus(Context context){
        List<PopupMenu> presented=new ArrayList<>();List<String> actions=new ArrayList<>();
        PhoneTaskMenu menu=new PhoneTaskMenu(context,presented::add);ImageButton anchor=new ImageButton(context);
        try{
            menu.show(anchor,actions::add);Menu first=presented.get(0).getMenu();
            check(first.size()==2&&first.findItem(PhoneTaskMenu.FLOAT).getTitle().equals(context.getString(R.string.phone_sidebar_float_task))&&first.findItem(PhoneTaskMenu.CLOSE).getTitle().equals(context.getString(R.string.phone_sidebar_close_task)),"localized task menu actions");
            first.performIdentifierAction(PhoneTaskMenu.FLOAT,0);check(actions.equals(Arrays.asList("float"))&&!menu.showing(),"float selection/dismiss");
            menu.show(anchor,actions::add);Menu stale=presented.get(1).getMenu();menu.close();
            stale.performIdentifierAction(PhoneTaskMenu.CLOSE,0);check(actions.size()==1,"detached popup close executed");
            menu.show(anchor,actions::add);Menu replaced=presented.get(2).getMenu();menu.show(anchor,actions::add);
            replaced.performIdentifierAction(PhoneTaskMenu.FLOAT,0);check(actions.size()==1,"replaced popup action executed");
            presented.get(3).getMenu().performIdentifierAction(PhoneTaskMenu.CLOSE,0);check(actions.equals(Arrays.asList("float","close")),"close selection");
            menu.show(anchor,true,actions::add);Menu floating=presented.get(4).getMenu();
            check(floating.size()==2&&floating.findItem(PhoneTaskMenu.FLOAT).getTitle().equals(context.getString(R.string.phone_sidebar_fullscreen_task))&&floating.findItem(PhoneTaskMenu.CLOSE).getTitle().equals(context.getString(R.string.phone_sidebar_close_task)),"floating task lacks return-to-main menu");
            floating.performIdentifierAction(PhoneTaskMenu.FLOAT,0);check(actions.equals(Arrays.asList("float","close","fullscreen")),"return-to-main menu reused float action");
            menu.show(anchor,TaskModes.canReturnToMain(2),actions::add);Menu pip=presented.get(5).getMenu();
            check(pip.findItem(PhoneTaskMenu.FLOAT).getTitle().equals(context.getString(R.string.phone_sidebar_fullscreen_task)),"PiP menu labels return-to-main as float");
            pip.performIdentifierAction(PhoneTaskMenu.FLOAT,0);check(actions.equals(Arrays.asList("float","close","fullscreen","fullscreen")),"PiP menu did not dispatch fullscreen");
        }finally{menu.close();}
    }
    /** Production legacy Taskbar commands, without constructing TaskSession or contacting the OS. */
    private static void taskbarActions(Context context)throws Exception{
        for(int mode:new int[]{1,2,5}){
            TaskSnapshot.Task selected=task(8800+mode,"fixture.taskbar/.Mode"+mode,true,mode);
            PopupMenu popup=new PopupMenu(context,new ImageButton(context));List<String> commands=new ArrayList<>();
            AppContextMenu.addTaskActions(context,popup.getMenu(),selected,action->commands.add(selected.id+"|"+selected.component+"|"+action));
            Menu menu=popup.getMenu();
            check(menu.findItem(AppContextMenu.CLOSE_TASK)!=null&&menu.findItem(AppContextMenu.CLOSE_TASK).getTitle().equals(context.getString(R.string.phone_sidebar_close_task)),"Legacy Taskbar lost direct localized task close");
            check(menu.performIdentifierAction(AppContextMenu.CLOSE_TASK,0)&&commands.equals(Arrays.asList(selected.id+"|"+selected.component+"|close")),"Legacy Taskbar close callback lost exact selected task/action");
            if(mode==2||mode==5){
                check(menu.findItem(AppContextMenu.RETURN_TO_MAIN)!=null&&menu.findItem(AppContextMenu.RETURN_TO_MAIN).getTitle().equals(context.getString(R.string.phone_sidebar_fullscreen_task)),"PiP/floating Taskbar lacks localized return-to-main");
                check(menu.performIdentifierAction(AppContextMenu.RETURN_TO_MAIN,0)&&commands.equals(Arrays.asList(selected.id+"|"+selected.component+"|close",selected.id+"|"+selected.component+"|fullscreen")),"Legacy Taskbar return callback lost exact selected task/action");
            }else check(menu.findItem(AppContextMenu.RETURN_TO_MAIN)==null,"Already-main Taskbar exposed a redundant return action");
        }
    }
    private static void rendering(Context context)throws Exception{
        List<TaskSnapshot.Task> clicked=new ArrayList<>(),menuTasks=new ArrayList<>();
        TaskSnapshot.Task first=task(401,"fixture.chat/.Main",true),second=task(402,"fixture.chat/.Main",false);
        LinearLayout rows=Ui.column(context);PhoneSidebar.renderTasks(context,rows,Arrays.asList(first,second),clicked::add,(anchor,task)->menuTasks.add(task));
        check(rows.getChildCount()==2,"same-app active tasks combined");
        ImageButton a=(ImageButton)rows.getChildAt(0),b=(ImageButton)rows.getChildAt(1);
        check(a.isSelected()&&!b.isSelected(),"focused task indicator");
        check(a.getContentDescription().equals(a.getTooltipText())&&b.getContentDescription().equals(b.getTooltipText()),"accessible/task tooltip mismatch");
        check(!a.getContentDescription().equals(b.getContentDescription()),"same-app task descriptions indistinguishable");
        check(a.getStateDescription().equals(context.getString(R.string.phone_sidebar_focused))&&b.getStateDescription().equals(context.getString(R.string.phone_sidebar_running)),"focus accessibility state");
        check(b.performClick()&&a.performClick()&&clicked.get(0)==second&&clicked.get(1)==first,"task click identity lost");
        check(b.performLongClick()&&menuTasks.get(0)==second&&clicked.size()==2,"long press mixed task identity or focused app");
        MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();pointer.id=0;pointer.toolType=MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords point=new MotionEvent.PointerCoords();point.x=10;point.y=10;
        MotionEvent rightClick=MotionEvent.obtain(0,0,MotionEvent.ACTION_BUTTON_PRESS,1,new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{point},0,MotionEvent.BUTTON_SECONDARY,1,1,0,0,InputDevice.SOURCE_MOUSE,0);
        try{check(a.dispatchGenericMotionEvent(rightClick)&&menuTasks.get(1)==first&&clicked.size()==2,"right click mixed task identity or focused app");}finally{rightClick.recycle();}
        PhoneSidebar.renderTasks(context,rows,new ArrayList<>(),clicked::add);check(rows.getChildCount()==0,"clear left stale active icons");
    }
}
