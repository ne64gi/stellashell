package net.fuyumori.stellashell.core.tasks;

import java.util.ArrayDeque;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.BooleanSupplier;

/** Main-thread operation lifetime and deferred handoffs, scoped to one session. */
public final class WorkspaceOperations {
    public WorkspaceOperations(){}
    private final ArrayDeque<Runnable> waiting=new ArrayDeque<>();
    private final Set<IdleRequest> idleRequests=new LinkedHashSet<>();
    private boolean draining;
    private boolean busy;
    private volatile long generation;
    public boolean busy(){return busy;}
    public long generation(){return generation;}
    public boolean defer(Runnable operation){if(!busy)return false;waiting.addLast(operation);return true;}
    /** Resolve the source at execution, after all earlier queued transfers have committed. */
    public void enqueueTransfer(IntSupplier currentSource,IntConsumer transfer){
        enqueueTransfer(currentSource,()->true,transfer,()->{});
    }
    /** Recovery eligibility is checked at execution too, before resolving or moving the source. */
    public void enqueueTransfer(IntSupplier currentSource,BooleanSupplier permitted,IntConsumer transfer,Runnable skipped){
        Runnable request=()->{if(permitted.getAsBoolean())transfer.accept(currentSource.getAsInt());else skipped.run();};
        if(!defer(request))request.run();
    }
    private final class IdleRequest implements AutoCloseable {
        private final Runnable callback;
        private boolean active=true;
        IdleRequest(Runnable callback){this.callback=callback;}
        @Override public void close(){active=false;idleRequests.remove(this);}
        void run(){if(!active)return;close();callback.run();}
    }
    /** One cancelable completion event, after commands and the FIFO queue are truly idle. */
    public AutoCloseable whenIdle(Runnable callback){
        IdleRequest request=new IdleRequest(callback);
        if(!busy&&!draining&&waiting.isEmpty()&&idleRequests.isEmpty())request.run();else idleRequests.add(request);
        return request;
    }
    public long begin(){busy=true;return generation;}
    public boolean current(long ticket){return ticket==generation;}
    public void requireCurrent(long ticket){if(!current(ticket))throw new IllegalStateException("Workspace session ended");}
    public boolean release(long ticket){if(!current(ticket))return false;busy=false;return true;}
    public void drain(){
        if(draining)return;
        draining=true;
        try{
            while(!busy){
                if(!waiting.isEmpty()){waiting.removeFirst().run();continue;}
                if(idleRequests.isEmpty())break;
                idleRequests.iterator().next().run();
            }
        }finally{draining=false;}
    }
    public void reset(){
        generation++;busy=false;waiting.clear();
        for(IdleRequest request:new ArrayList<>(idleRequests))request.close();
    }

    public interface Restore {void run(int id)throws Exception;}
    public static Exception rollback(List<Integer> moved,Restore restore,Exception failure){
        IllegalStateException incomplete=null;
        for(int id:moved)try{restore.run(id);}catch(Exception error){
            if(incomplete==null)incomplete=new IllegalStateException("Transfer failed; rollback incomplete",failure);
            incomplete.addSuppressed(new IllegalStateException("Task "+id+": "+error.getMessage(),error));
        }
        return incomplete==null?failure:incomplete;
    }
}
