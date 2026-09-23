package chatty.util.api.queue;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.*;

public class RequestExecutionTest {
    @Test
    public void callbackFailureReleasesSlotAndAllowsIdenticalRequestAgain() {
        Set<Entry> pending = new HashSet<>();
        Semaphore slots = new Semaphore(0);
        AtomicInteger callbacks = new AtomicInteger();
        Entry entry = entry(listener -> listener.requestResult("ok", 200, null, 42), result -> {
            callbacks.incrementAndGet();
            assertEquals(1, slots.availablePermits());
            throw new IllegalStateException("synthetic handler failure");
        });
        pending.add(entry);
        AtomicInteger quota = new AtomicInteger();
        new RequestExecution(entry, quota::set, slots::release, () -> pending.remove(entry)).run();
        assertEquals(42, quota.get());
        assertEquals(1, callbacks.get());
        assertEquals(1, slots.availablePermits());
        assertTrue(pending.isEmpty());
        assertTrue(pending.add(entry(listener -> {}, result -> {})));
    }

    @Test
    public void requestErrorsAndMissingCallbacksCompleteAsFailureOnce() {
        checkFailure(listener -> { throw new IllegalArgumentException("synthetic request failure"); });
        checkFailure(listener -> {});
    }

    @Test
    public void duplicateCompletionAndLateRequestErrorDoNotRepeatCallbackOrRelease() {
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger cleanup = new AtomicInteger();
        Entry entry = entry(listener -> {
            listener.requestResult("ok", 200, null, 10);
            listener.requestResult("duplicate", 200, null, 9);
            throw new IllegalStateException("after completion");
        }, result -> { assertEquals("ok", result.text); callbacks.incrementAndGet(); });
        new RequestExecution(entry, remaining -> {}, releases::incrementAndGet, cleanup::incrementAndGet).run();
        assertEquals(1, callbacks.get());
        assertEquals(1, releases.get());
        assertEquals(1, cleanup.get());
    }

    @Test
    public void executorRejectionCompletesWithoutExecutingOrRetryingRequest() {
        AtomicInteger sends = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger cleanup = new AtomicInteger();
        Entry entry = entry(listener -> sends.incrementAndGet(), result -> {
            assertEquals(-1, result.responseCode);
            callbacks.incrementAndGet();
        });
        RequestExecution execution = new RequestExecution(entry, remaining -> {}, releases::incrementAndGet, cleanup::incrementAndGet);
        execution.rejected(new RejectedExecutionException("synthetic rejection"));
        assertEquals(0, sends.get());
        assertEquals(1, callbacks.get());
        assertEquals(1, releases.get());
        assertEquals(1, cleanup.get());
    }

    private void checkFailure(Consumer<RequestResultListener> behavior) {
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger cleanup = new AtomicInteger();
        Entry entry = entry(behavior, result -> { assertEquals(-1, result.responseCode); callbacks.incrementAndGet(); });
        new RequestExecution(entry, remaining -> {}, releases::incrementAndGet, cleanup::incrementAndGet).run();
        assertEquals(1, callbacks.get());
        assertEquals(1, releases.get());
        assertEquals(1, cleanup.get());
    }

    private Entry entry(Consumer<RequestResultListener> behavior, ResultListener result) {
        Request request = new Request("https://example.invalid/synthetic") {
            private RequestResultListener callback;
            @Override public void setResultListener(RequestResultListener listener) { callback = listener; }
            @Override public void run() { behavior.accept(callback); }
        };
        return new Entry(1, request, result);
    }
}
