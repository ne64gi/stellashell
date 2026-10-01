package net.fuyumori.stellashell;

import java.util.*;

/** Session-only intent, keyed by task token as well as id. Native WM owns z-order and input. */
final class TaskPins {
    interface Access {
        List<FrameworkTaskAccess.Entry> all()throws Exception;
        void set(FrameworkTaskAccess.Entry task,boolean enabled)throws Exception;
    }
    private static final class Pin {
        final int id;final Object token;final String component;boolean suspended;
        Pin(FrameworkTaskAccess.Entry task){id=task.id;token=task.token;component=task.component.flattenToString();}
        boolean matches(FrameworkTaskAccess.Entry task){return id==task.id&&token.equals(task.token)&&task.component!=null&&component.equals(task.component.flattenToString());}
    }
    private final Access access;private final Map<Integer,Pin> pins=new HashMap<>();
    TaskPins(Access access){this.access=access;}
    boolean pinned(FrameworkTaskAccess.Entry task){Pin pin=pins.get(task.id);return pin!=null&&pin.matches(task)||task.windowMode==5&&task.alwaysOnTop;}
    void set(FrameworkTaskAccess.Entry task,boolean enabled)throws Exception {
        Pin previous=pins.get(task.id);
        if(enabled)pins.put(task.id,new Pin(task)); // Retain ownership if dispatch partly succeeds.
        try{access.set(task,enabled);}
        catch(Exception failure){
            if(enabled)try{
                access.set(task,task.alwaysOnTop);
                if(previous==null)pins.remove(task.id);else pins.put(task.id,previous);
            }catch(Exception rollback){failure.addSuppressed(rollback);}
            throw failure;
        }
        if(!enabled)pins.remove(task.id);
    }
    void suspend(FrameworkTaskAccess.Entry task)throws Exception {
        if(!pinned(task))return;Pin pin=pins.get(task.id);
        if(pin==null||!pin.matches(task))pin=new Pin(task);
        access.set(task,false);pin.suspended=true;pins.put(task.id,pin);
    }
    void resume(FrameworkTaskAccess.Entry task)throws Exception {
        Pin pin=pins.get(task.id);if(pin==null||!pin.matches(task)||!pin.suspended||task.windowMode!=5)return;
        access.set(task,true);pin.suspended=false;
    }
    void fullscreen(FrameworkTaskAccess.Entry task)throws Exception {if(pinned(task))set(task,false);}
    void closed(int id){pins.remove(id);}
    void reconcile()throws Exception {
        if(pins.isEmpty())return;
        Map<Integer,FrameworkTaskAccess.Entry> live=new HashMap<>();for(FrameworkTaskAccess.Entry task:access.all())live.put(task.id,task);
        for(Pin pin:new ArrayList<>(pins.values())){
            FrameworkTaskAccess.Entry task=live.get(pin.id);
            if(task==null||!pin.matches(task)){pins.remove(pin.id);continue;}
            if(task.windowMode!=5){access.set(task,false);pins.remove(pin.id);continue;}
            if(pin.suspended&&task.visible&&task.focused)resume(task);
            else if(!pin.suspended&&!task.alwaysOnTop)pins.remove(pin.id); // Respect external changes.
        }
    }
    void release()throws Exception {
        if(pins.isEmpty())return;
        Map<Integer,FrameworkTaskAccess.Entry> live=new HashMap<>();for(FrameworkTaskAccess.Entry task:access.all())live.put(task.id,task);
        Exception failure=null;
        for(Pin pin:new ArrayList<>(pins.values()))try{
            FrameworkTaskAccess.Entry task=live.get(pin.id);if(task!=null&&pin.matches(task))access.set(task,false);pins.remove(pin.id);
        }catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        if(failure!=null)throw failure;
    }
}
