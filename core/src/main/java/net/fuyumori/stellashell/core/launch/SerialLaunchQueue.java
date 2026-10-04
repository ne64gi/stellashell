package net.fuyumori.stellashell.core.launch;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Process-lived FIFO owner. All calls, scheduler callbacks and completions use one main loop. */
public final class SerialLaunchQueue {
    public interface Scheduler {
        void postDelayed(Runnable action,long delayMillis);
        void remove(Runnable action);
    }
    public interface Action { void run(Completion completion); }
    public interface Completion { void finish(); }

    private static final long RETRY_MILLIS=100;
    private static final class Job {
        final BooleanSupplier busy;
        final Action action;
        final Consumer<RuntimeException> failed;
        boolean finished;
        Job(BooleanSupplier busy,Action action,Consumer<RuntimeException> failed){
            this.busy=busy;this.action=action;this.failed=failed;
        }
    }
    private final Scheduler scheduler;
    private final ArrayDeque<Job> waiting=new ArrayDeque<>();
    private final Runnable retry;
    private Job active;
    private boolean draining,retryScheduled;

    public SerialLaunchQueue(Scheduler scheduler){
        this.scheduler=Objects.requireNonNull(scheduler);
        retry=()->{retryScheduled=false;drain();};
    }

    public void enqueue(BooleanSupplier busy,Action action,Consumer<RuntimeException> failed){
        waiting.addLast(new Job(Objects.requireNonNull(busy),Objects.requireNonNull(action),Objects.requireNonNull(failed)));
        drain();
    }

    /** Includes the active job, waiting jobs and a busy-gated head awaiting its retry. */
    public boolean pending(){return active!=null||!waiting.isEmpty();}

    private void finish(Job job){
        if(job.finished)return;
        job.finished=true;
        if(active==job){active=null;drain();}
    }

    private void drain(){
        // Reentrant enqueue/finish never starts the next action on the current action's stack.
        if(draining)return;
        draining=true;
        Throwable reportingFailure=null;
        try{
            if(retryScheduled){scheduler.remove(retry);retryScheduled=false;}
            while(active==null&&!waiting.isEmpty()){
                Job job=waiting.peekFirst();
                boolean busy;
                try{busy=job.busy.getAsBoolean();}
                catch(RuntimeException failure){
                    waiting.removeFirst();job.finished=true;
                    reportingFailure=report(job,failure,reportingFailure);
                    continue;
                }
                if(busy){retryScheduled=true;scheduler.postDelayed(retry,RETRY_MILLIS);break;}
                waiting.removeFirst();active=job;
                try{job.action.run(()->finish(job));}
                catch(RuntimeException failure){
                    finish(job);
                    reportingFailure=report(job,failure,reportingFailure);
                }
            }
        }finally{draining=false;}
        // Report callback bugs only after the owner is released and subsequent work is drained.
        if(reportingFailure instanceof Error)throw (Error)reportingFailure;
        if(reportingFailure!=null)throw (RuntimeException)reportingFailure;
    }

    private static Throwable report(Job job,RuntimeException failure,Throwable previous){
        try{job.failed.accept(failure);}
        catch(RuntimeException|Error callbackFailure){
            if(previous==null)return callbackFailure;
            if(previous!=callbackFailure)previous.addSuppressed(callbackFailure);
        }
        return previous;
    }
}
