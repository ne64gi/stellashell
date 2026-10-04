package net.fuyumori.stellashell.core.tasks;

import java.util.ArrayDeque;
import java.util.List;

/** Main-thread operation lifetime and deferred handoffs, scoped to one session. */
public final class WorkspaceOperations {
    public WorkspaceOperations(){}
    private final ArrayDeque<Runnable> waiting=new ArrayDeque<>();
    private boolean busy;
    private volatile long generation;
    public boolean busy(){return busy;}
    public long generation(){return generation;}
    public boolean defer(Runnable operation){if(!busy)return false;waiting.addLast(operation);return true;}
    public long begin(){busy=true;return generation;}
    public boolean current(long ticket){return ticket==generation;}
    public void requireCurrent(long ticket){if(!current(ticket))throw new IllegalStateException("Workspace session ended");}
    public boolean release(long ticket){if(!current(ticket))return false;busy=false;return true;}
    public void drain(){while(!busy&&!waiting.isEmpty())waiting.removeFirst().run();}
    public void reset(){generation++;busy=false;waiting.clear();}

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
