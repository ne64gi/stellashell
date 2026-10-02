package net.fuyumori.stellashell;

import java.util.ArrayDeque;
import java.util.List;

/** Main-thread operation lifetime and deferred handoffs, scoped to one session. */
final class WorkspaceOperations {
    private final ArrayDeque<Runnable> waiting=new ArrayDeque<>();
    private boolean busy;
    private volatile long generation;
    boolean busy(){return busy;}
    long generation(){return generation;}
    boolean defer(Runnable operation){if(!busy)return false;waiting.addLast(operation);return true;}
    long begin(){busy=true;return generation;}
    boolean current(long ticket){return ticket==generation;}
    void requireCurrent(long ticket){if(!current(ticket))throw new IllegalStateException("Workspace session ended");}
    boolean release(long ticket){if(!current(ticket))return false;busy=false;return true;}
    void drain(){while(!busy&&!waiting.isEmpty())waiting.removeFirst().run();}
    void reset(){generation++;busy=false;waiting.clear();}

    interface Restore {void run(int id)throws Exception;}
    static Exception rollback(List<Integer> moved,Restore restore,Exception failure){
        IllegalStateException incomplete=null;
        for(int id:moved)try{restore.run(id);}catch(Exception error){
            if(incomplete==null)incomplete=new IllegalStateException("Transfer failed; rollback incomplete",failure);
            incomplete.addSuppressed(new IllegalStateException("Task "+id+": "+error.getMessage(),error));
        }
        return incomplete==null?failure:incomplete;
    }
}
