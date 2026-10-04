package net.fuyumori.stellashell.core.launch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SerialLaunchQueueTest {
    private static final class Scheduler implements SerialLaunchQueue.Scheduler {
        final ArrayDeque<Runnable> callbacks=new ArrayDeque<>();
        final List<Long> delays=new ArrayList<>();
        public void postDelayed(Runnable action,long delayMillis){callbacks.addLast(action);delays.add(delayMillis);}
        public void remove(Runnable action){callbacks.removeIf(callback->callback==action);}
        void tick(){callbacks.removeFirst().run();}
    }
    private static void unexpected(RuntimeException failure){throw new AssertionError("Unexpected launch failure",failure);}

    @Test public void asyncJobsRemainFifoAndOldCompletionsCannotReleaseTheNextJob(){
        Scheduler scheduler=new Scheduler();SerialLaunchQueue queue=new SerialLaunchQueue(scheduler);
        List<Integer> started=new ArrayList<>();List<SerialLaunchQueue.Completion> completions=new ArrayList<>();
        for(int i=0;i<3;i++){
            int id=i;queue.enqueue(()->false,done->{started.add(id);completions.add(done);},SerialLaunchQueueTest::unexpected);
        }
        assertEquals(Arrays.asList(0),started);assertTrue(queue.pending());
        completions.get(0).finish();assertEquals(Arrays.asList(0,1),started);
        completions.get(0).finish();assertEquals(Arrays.asList(0,1),started);assertTrue(queue.pending());
        completions.get(1).finish();assertEquals(Arrays.asList(0,1,2),started);
        completions.get(0).finish();completions.get(1).finish();assertTrue(queue.pending());
        completions.get(2).finish();completions.get(2).finish();assertFalse(queue.pending());
        assertTrue(scheduler.callbacks.isEmpty());
    }

    @Test public void busyHeadBlocksLaterReadyJobsAndOwnsOnlyOneHundredMillisRetry(){
        Scheduler scheduler=new Scheduler();SerialLaunchQueue queue=new SerialLaunchQueue(scheduler);
        boolean[] busy={true};int[] laterGateChecks={0};List<Integer> started=new ArrayList<>();
        queue.enqueue(()->busy[0],done->{started.add(0);done.finish();},SerialLaunchQueueTest::unexpected);
        for(int i=1;i<=4;i++){
            int id=i;queue.enqueue(()->{laterGateChecks[0]++;return false;},done->{started.add(id);done.finish();},SerialLaunchQueueTest::unexpected);
            assertEquals(1,scheduler.callbacks.size());
        }
        assertTrue(queue.pending());assertTrue(started.isEmpty());assertEquals(0,laterGateChecks[0]);
        scheduler.tick();assertEquals(1,scheduler.callbacks.size());assertTrue(started.isEmpty());
        for(long delay:scheduler.delays)assertEquals(100L,delay);
        busy[0]=false;scheduler.tick();
        assertEquals(Arrays.asList(0,1,2,3,4),started);assertEquals(4,laterGateChecks[0]);
        assertFalse(queue.pending());assertTrue(scheduler.callbacks.isEmpty());
    }

    @Test public void waitingGateIsNotReadUntilActiveJobFinishesAndAReadyDrainCancelsRetry(){
        Scheduler scheduler=new Scheduler();SerialLaunchQueue queue=new SerialLaunchQueue(scheduler);
        SerialLaunchQueue.Completion[] active={null};int[] checks={0};boolean[] busy={true};
        queue.enqueue(()->false,done->active[0]=done,SerialLaunchQueueTest::unexpected);
        queue.enqueue(()->{checks[0]++;return busy[0];},SerialLaunchQueue.Completion::finish,SerialLaunchQueueTest::unexpected);
        assertEquals(0,checks[0]);assertTrue(scheduler.callbacks.isEmpty());
        active[0].finish();assertEquals(1,checks[0]);assertEquals(1,scheduler.callbacks.size());
        busy[0]=false;queue.enqueue(()->false,SerialLaunchQueue.Completion::finish,SerialLaunchQueueTest::unexpected);
        assertFalse(queue.pending());assertTrue(scheduler.callbacks.isEmpty());
    }

    @Test public void reentrantEnqueuesKeepFifoAndRunAfterTheCurrentActionUnwinds(){
        SerialLaunchQueue queue=new SerialLaunchQueue(new Scheduler());List<String> events=new ArrayList<>();
        queue.enqueue(()->false,done->{
            events.add("first");
            queue.enqueue(()->false,next->{
                events.add("second");
                queue.enqueue(()->false,last->{events.add("fourth");last.finish();},SerialLaunchQueueTest::unexpected);
                next.finish();events.add("second returned");
            },SerialLaunchQueueTest::unexpected);
            queue.enqueue(()->false,next->{events.add("third");next.finish();},SerialLaunchQueueTest::unexpected);
            done.finish();events.add("first returned");
        },SerialLaunchQueueTest::unexpected);
        assertEquals(Arrays.asList("first","first returned","second","second returned","third","fourth"),events);
        assertFalse(queue.pending());
    }

    @Test public void fiveThousandSynchronousJobsDrainWithoutGrowingTheActionStack(){
        SerialLaunchQueue queue=new SerialLaunchQueue(new Scheduler());int[] depth={0},maximum={0},ran={0};
        queue.enqueue(()->false,first->{
            depth[0]++;maximum[0]=Math.max(maximum[0],depth[0]);
            for(int i=0;i<5000;i++)queue.enqueue(()->false,done->{
                depth[0]++;maximum[0]=Math.max(maximum[0],depth[0]);ran[0]++;
                done.finish();depth[0]--;
            },SerialLaunchQueueTest::unexpected);
            first.finish();depth[0]--;
        },SerialLaunchQueueTest::unexpected);
        assertEquals(5000,ran[0]);assertEquals(1,maximum[0]);assertEquals(0,depth[0]);assertFalse(queue.pending());
    }

    @Test public void actionFailureFinishesItsJobReportsTheOriginalErrorAndRecovers(){
        SerialLaunchQueue queue=new SerialLaunchQueue(new Scheduler());List<RuntimeException> failures=new ArrayList<>();
        RuntimeException error=new IllegalStateException("launch rejected");SerialLaunchQueue.Completion[] first={null},failed={null},last={null};
        queue.enqueue(()->false,done->first[0]=done,SerialLaunchQueueTest::unexpected);
        queue.enqueue(()->false,done->{failed[0]=done;throw error;},failures::add);
        queue.enqueue(()->false,done->last[0]=done,SerialLaunchQueueTest::unexpected);
        first[0].finish();assertEquals(Arrays.asList(error),failures);assertNotNull(last[0]);assertTrue(queue.pending());
        failed[0].finish();assertTrue(queue.pending());last[0].finish();assertFalse(queue.pending());
    }

    @Test public void throwingFailureCallbackCannotWedgeQueuedOrFutureWork(){
        SerialLaunchQueue queue=new SerialLaunchQueue(new Scheduler());SerialLaunchQueue.Completion[] first={null};int[] ran={0};
        RuntimeException actionFailure=new IllegalArgumentException("action"),callbackFailure=new IllegalStateException("reporter");
        queue.enqueue(()->false,done->first[0]=done,SerialLaunchQueueTest::unexpected);
        queue.enqueue(()->false,done->{throw actionFailure;},failure->{assertSame(actionFailure,failure);throw callbackFailure;});
        queue.enqueue(()->false,done->{ran[0]++;done.finish();},SerialLaunchQueueTest::unexpected);
        assertSame(callbackFailure,assertThrows(RuntimeException.class,first[0]::finish));
        assertEquals(1,ran[0]);assertFalse(queue.pending());
        queue.enqueue(()->false,done->{ran[0]++;done.finish();},SerialLaunchQueueTest::unexpected);
        assertEquals(2,ran[0]);assertFalse(queue.pending());
    }

    @Test public void failureCallbackCanEnqueueWithoutSkippingAnOlderWaitingJob(){
        SerialLaunchQueue queue=new SerialLaunchQueue(new Scheduler());SerialLaunchQueue.Completion[] first={null};List<Integer> events=new ArrayList<>();
        queue.enqueue(()->false,done->first[0]=done,SerialLaunchQueueTest::unexpected);
        queue.enqueue(()->false,done->{throw new IllegalStateException("failed");},failure->{
            events.add(0);queue.enqueue(()->false,done->{events.add(2);done.finish();},SerialLaunchQueueTest::unexpected);
        });
        queue.enqueue(()->false,done->{events.add(1);done.finish();},SerialLaunchQueueTest::unexpected);
        first[0].finish();assertEquals(Arrays.asList(0,1,2),events);assertFalse(queue.pending());
    }

    @Test public void failureCallbackErrorAlsoDrainsWaitingWorkBeforePropagating(){
        SerialLaunchQueue queue=new SerialLaunchQueue(new Scheduler());SerialLaunchQueue.Completion[] first={null};int[] ran={0};
        AssertionError callbackError=new AssertionError("reporter failed");
        queue.enqueue(()->false,done->first[0]=done,SerialLaunchQueueTest::unexpected);
        queue.enqueue(()->false,done->{throw new IllegalStateException("action");},failure->{throw callbackError;});
        queue.enqueue(()->false,done->{ran[0]++;done.finish();},SerialLaunchQueueTest::unexpected);
        assertSame(callbackError,assertThrows(AssertionError.class,first[0]::finish));
        assertEquals(1,ran[0]);assertFalse(queue.pending());
    }

    @Test public void brokenBusyGateIsRejectedAndDoesNotRemainAtTheHeadForever(){
        Scheduler scheduler=new Scheduler();SerialLaunchQueue queue=new SerialLaunchQueue(scheduler);
        RuntimeException error=new IllegalStateException("lost session");List<RuntimeException> failures=new ArrayList<>();int[] ran={0};
        queue.enqueue(()->{throw error;},done->fail("A rejected gate must not run its action"),failures::add);
        queue.enqueue(()->false,done->{ran[0]++;done.finish();},SerialLaunchQueueTest::unexpected);
        assertEquals(Arrays.asList(error),failures);assertEquals(1,ran[0]);assertFalse(queue.pending());assertTrue(scheduler.callbacks.isEmpty());
    }

    @Test public void completingThenThrowingReportsFailureOnlyOnceWithoutReleasingALaterJob(){
        SerialLaunchQueue queue=new SerialLaunchQueue(new Scheduler());SerialLaunchQueue.Completion[] failed={null},next={null};
        List<RuntimeException> failures=new ArrayList<>();RuntimeException error=new IllegalStateException("after completion");
        queue.enqueue(()->false,done->{
            failed[0]=done;
            queue.enqueue(()->false,later->next[0]=later,SerialLaunchQueueTest::unexpected);
            done.finish();throw error;
        },failures::add);
        assertEquals(Arrays.asList(error),failures);assertNotNull(next[0]);assertTrue(queue.pending());
        failed[0].finish();assertTrue(queue.pending());assertEquals(1,failures.size());
        next[0].finish();assertFalse(queue.pending());
    }
}
