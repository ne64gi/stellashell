package net.fuyumori.stellashell.core.display;

import java.util.Collection;
import net.fuyumori.stellashell.core.launch.Policy;

/** Event-driven AUTO decision lifetime; owns no settings, tasks, or timer. */
public final class AutoOutputReconciler {
    public static final class Ticket {
        private final long session;
        private final int target;
        private Ticket(long session,int target){this.session=session;this.target=target;}
    }
    private Ticket queued,transferring;
    private boolean requestedDuringTransfer;
    public boolean inFlight(){return transferring!=null;}

    /** A later event replaces the queued decision, never an in-flight transfer. */
    public Ticket request(long session,int target){
        // Workspace reset cancels its commands and may intentionally omit their callbacks.
        if(transferring!=null&&transferring.session!=session){transferring=null;requestedDuringTransfer=false;}
        if(transferring!=null){requestedDuringTransfer=true;return null;}
        return queued=new Ticket(session,target);
    }
    public void cancel(){queued=null;requestedDuringTransfer=false;}
    /** Target persistence invalidates queued decisions, not real events awaiting this transfer. */
    public void routingChanged(long session){
        queued=null;
        if(transferring!=null&&transferring.session!=session){transferring=null;requestedDuringTransfer=false;}
    }
    public boolean current(Ticket ticket,long session,int target){
        return ticket!=null&&ticket==queued&&ticket.session==session&&ticket.target==target;
    }

    /** Candidates must be fresh public, valid external IDs supplied by the platform. */
    public int begin(Ticket ticket,long session,int target,boolean eligible,int preferred,
            Collection<Integer> candidates){
        if(!current(ticket,session,target))return -1;
        queued=null;
        if(transferring!=null||ticket.session!=session||ticket.target!=target||target!=0||!eligible)return -1;
        int destination=Policy.selectDisplay(preferred,candidates);
        if(destination>0)transferring=ticket;
        return destination;
    }
    /** Replay an actual event deferred during transfer, not an unconditional retry. */
    public boolean finished(Ticket ticket){
        if(transferring!=ticket)return false;
        transferring=null;
        boolean reconcile=requestedDuringTransfer;requestedDuringTransfer=false;return reconcile;
    }
}
