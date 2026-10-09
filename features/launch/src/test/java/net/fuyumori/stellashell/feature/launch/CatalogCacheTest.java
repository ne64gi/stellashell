package net.fuyumori.stellashell.feature.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class CatalogCacheTest {
    @Test public void snapshotIsDetachedImmutableAndReusedUntilInvalidated() {
        List<String> source = new ArrayList<>(Arrays.asList("first", "second"));
        AtomicInteger reads = new AtomicInteger();
        CatalogCache<String> cache = new CatalogCache<>(() -> {
            reads.incrementAndGet();
            return source;
        });

        assertNull(cache.peek());
        List<String> first = cache.get();
        source.clear();
        assertEquals(Arrays.asList("first", "second"), first);
        try {
            first.add("third");
            fail("Snapshot must reject membership changes");
        } catch (UnsupportedOperationException expected) {
            // List membership belongs to the cache.
        }
        assertSame(first, cache.peek());
        assertSame(first, cache.get());
        assertEquals(1, reads.get());

        cache.invalidate();
        assertNull(cache.peek());
        assertTrue(cache.get().isEmpty());
        assertEquals(2, reads.get());
    }

    @Test(timeout = 10000) public void simultaneousWorkerReadsShareOneLoad() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch callersReady = new CountDownLatch(3);
        CatalogCache<String> cache = new CatalogCache<>(() -> {
            reads.incrementAndGet();
            started.countDown();
            await(finish);
            return Collections.singletonList("shared");
        });
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            Future<List<String>> owner = workers.submit(cache::get);
            await(started);
            List<Future<List<String>>> followers = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                followers.add(workers.submit(() -> {
                    callersReady.countDown();
                    return cache.get();
                }));
            }
            await(callersReady);
            assertNull(cache.peek());
            assertEquals(1, reads.get());
            finish.countDown();
            List<String> snapshot = result(owner);
            for (Future<List<String>> follower : followers) assertSame(snapshot, result(follower));
            assertSame(snapshot, cache.peek());
            assertEquals(1, reads.get());
        } finally {
            finish.countDown();
            stop(workers);
        }
    }

    @Test(timeout = 10000) public void invalidatedLoadAndReadersJoinFreshGeneration() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch oldStarted = new CountDownLatch(1);
        CountDownLatch finishOld = new CountDownLatch(1);
        CountDownLatch readerReady = new CountDownLatch(1);
        CountDownLatch freshStarted = new CountDownLatch(1);
        CountDownLatch finishFresh = new CountDownLatch(1);
        CatalogCache<String> cache = new CatalogCache<>(() -> {
            if (reads.incrementAndGet() == 1) {
                oldStarted.countDown();
                await(finishOld);
                return Collections.singletonList("obsolete");
            }
            freshStarted.countDown();
            await(finishFresh);
            return Collections.singletonList("fresh");
        });
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<List<String>> oldOwner = workers.submit(cache::get);
            await(oldStarted);
            Future<List<String>> reader = workers.submit(() -> {
                readerReady.countDown();
                return cache.get();
            });
            await(readerReady);
            cache.invalidate();
            assertNull(cache.peek());
            Future<List<String>> freshReader = workers.submit(cache::get);
            // The fresh generation must start while the obsolete loader is still blocked.
            await(freshStarted);
            assertEquals(2, reads.get());
            assertNull(cache.peek());
            finishOld.countDown();
            finishFresh.countDown();
            List<String> snapshot = result(freshReader);
            assertEquals(Collections.singletonList("fresh"), snapshot);
            assertSame(snapshot, result(oldOwner));
            assertSame(snapshot, result(reader));
            assertSame(snapshot, cache.peek());
            assertEquals(2, reads.get());
        } finally {
            finishOld.countDown();
            finishFresh.countDown();
            stop(workers);
        }
    }

    @Test(timeout = 10000) public void exceptionAndFatalErrorReleaseReadersAndPermitRetry() throws Exception {
        assertFailedLoadAllowsRetry(new IllegalArgumentException("loader failed"));
        assertFailedLoadAllowsRetry(new AssertionError("fatal loader failure"));
    }

    private static void assertFailedLoadAllowsRetry(Throwable failure) throws Exception {
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch failLoad = new CountDownLatch(1);
        CountDownLatch readerReady = new CountDownLatch(1);
        CatalogCache<String> cache = new CatalogCache<>(() -> {
            if (reads.incrementAndGet() == 1) {
                started.countDown();
                await(failLoad);
                if (failure instanceof Error) throw (Error) failure;
                throw (RuntimeException) failure;
            }
            return Collections.singletonList("retry");
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<List<String>> owner = workers.submit(cache::get);
            await(started);
            Future<List<String>> reader = workers.submit(() -> {
                readerReady.countDown();
                return cache.get();
            });
            await(readerReady);
            failLoad.countDown();
            try {
                result(owner);
                fail("Loader failure must propagate to its owner");
            } catch (ExecutionException expected) {
                assertSame(failure, expected.getCause());
            }
            List<String> snapshot = result(reader);
            assertEquals(Collections.singletonList("retry"), snapshot);
            assertSame(snapshot, cache.get());
            assertEquals(2, reads.get());
        } finally {
            failLoad.countDown();
            stop(workers);
        }
    }

    @Test(timeout = 10000) public void interruptedWaiterPreservesFlagWithoutPoisoningOwner() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        CatalogCache<String> cache = new CatalogCache<>(() -> {
            reads.incrementAndGet();
            started.countDown();
            await(finish);
            return Collections.singletonList("loaded");
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<List<String>> owner = workers.submit(cache::get);
            await(started);
            Future<Boolean> interruptedReader = workers.submit(() -> {
                Thread.currentThread().interrupt();
                try {
                    cache.get();
                    return false;
                } catch (IllegalStateException expected) {
                    assertTrue(expected.getCause() instanceof InterruptedException);
                    return Thread.currentThread().isInterrupted();
                } finally {
                    Thread.interrupted();
                }
            });
            assertTrue(result(interruptedReader));
            assertFalse(owner.isDone());
            finish.countDown();
            assertSame(result(owner), cache.get());
            assertEquals(1, reads.get());
        } finally {
            finish.countDown();
            stop(workers);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue("Worker did not reach its gate", latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static <T> T result(Future<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static void stop(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        assertTrue("Worker pool did not stop", workers.awaitTermination(5, TimeUnit.SECONDS));
    }
}
