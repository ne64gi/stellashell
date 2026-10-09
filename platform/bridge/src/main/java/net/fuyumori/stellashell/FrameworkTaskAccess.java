package net.fuyumori.stellashell;

import android.app.TaskInfo;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.graphics.Rect;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Framework boundary for task snapshots. See docs/TASK-API-PROVENANCE.md.
 * Resolves a fixed Android schema once, then materializes detached records;
 * backend policy never reflects through arbitrary task/configuration objects.
 */
final class FrameworkTaskAccess {
    static final class Entry {
        final int id, displayId, userId, windowMode, activityType;
        final ComponentName component, topComponent;
        final boolean visible, focused, alwaysOnTop;
        final Object token;
        final Rect bounds;
        Entry(TaskInfo source, int display, int user, int mode, int type,
                boolean visible, boolean focused, boolean alwaysOnTop, Object token, Rect area) {
            id=source.taskId;displayId=display;userId=user;windowMode=mode;activityType=type;
            component=source.baseActivity!=null?source.baseActivity:source.topActivity;
            topComponent=source.topActivity;
            this.visible=visible;this.focused=focused;this.alwaysOnTop=alwaysOnTop;this.token=token;
            bounds=new Rect(area);
        }
    }
    static final class Root {
        final Entry task;
        final int[] children;
        Root(Entry task,int[] children){this.task=task;this.children=children.clone();}
    }

    private final Object service;
    private final Method enumerate;
    private final Method configuration=TaskInfo.class.getMethod("getConfiguration");
    private final Method taskToken=TaskInfo.class.getMethod("getToken");
    private final Field display=TaskInfo.class.getField("displayId");
    private final Field user=TaskInfo.class.getField("userId");
    private final Field visible=TaskInfo.class.getField("isVisible");
    private final Field focused=TaskInfo.class.getField("isFocused");
    private final Field window=Configuration.class.getField("windowConfiguration");
    private final Method area=window.getType().getMethod("getBounds");
    private final Method mode, activityType;
    private final Method alwaysOnTop=window.getType().getMethod("isAlwaysOnTop");
    private final boolean taskGetters;
    private final Class<?> serviceApi;

    FrameworkTaskAccess(Class<?> serviceApi,Object service)throws ReflectiveOperationException {
        this.serviceApi=serviceApi;this.service=service;
        enumerate=serviceApi.getMethod("getTasks",int.class,boolean.class,boolean.class,int.class);
        Method wm,at;boolean direct;
        try {
            wm=TaskInfo.class.getMethod("getWindowingMode");
            at=TaskInfo.class.getMethod("getActivityType");direct=true;
        } catch(NoSuchMethodException olderFramework) {
            // Android 11 lacks the TaskInfo convenience getters. Resolve the
            // documented framework WindowConfiguration contract, not OEM fields.
            wm=window.getType().getMethod("getWindowingMode");
            at=window.getType().getMethod("getActivityType");direct=false;
        }
        mode=wm;activityType=at;taskGetters=direct;
    }
    Entry decode(TaskInfo source)throws ReflectiveOperationException {
        Configuration config=(Configuration)configuration.invoke(source);
        Object windowState=window.get(config);
        Object receiver=taskGetters?source:windowState;
        return new Entry(source,display.getInt(source),user.getInt(source),
                (Integer)mode.invoke(receiver),(Integer)activityType.invoke(receiver),
                visible.getBoolean(source),focused.getBoolean(source),(Boolean)alwaysOnTop.invoke(windowState),taskToken.invoke(source),
                (Rect)area.invoke(windowState));
    }
    List<Entry> query(int displayId)throws ReflectiveOperationException {
        List<?> reply=(List<?>)enumerate.invoke(service,100,false,false,displayId);
        List<Entry> result=new ArrayList<>(reply.size());
        for(Object item:reply)result.add(decode(TaskInfo.class.cast(item)));
        return result;
    }
    List<Root> roots(int displayId)throws ReflectiveOperationException {
        // Optional capability: failure here must not disable the task list.
        Class<?> rootType=Class.forName("android.app.ActivityTaskManager$RootTaskInfo");
        Field childIds=rootType.getField("childTaskIds");
        List<?> reply=(List<?>)serviceApi.getMethod("getAllRootTaskInfosOnDisplay",int.class).invoke(service,displayId);
        List<Root> result=new ArrayList<>(reply.size());
        for(Object item:reply)result.add(new Root(decode(TaskInfo.class.cast(item)),(int[])childIds.get(item)));
        return result;
    }
}
