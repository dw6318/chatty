package chatty.util.api.queue;

import chatty.util.Debugging;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Completes a synchronous API request once, including all failure paths. */
final class RequestExecution implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(RequestExecution.class.getName());
    private final Entry entry;
    private final IntConsumer quota;
    private final Runnable release;
    private final Runnable cleanup;
    private final AtomicBoolean completed = new AtomicBoolean();

    RequestExecution(Entry entry, IntConsumer quota, Runnable release, Runnable cleanup) {
        this.entry = entry;
        this.quota = quota;
        this.release = release;
        this.cleanup = cleanup;
    }

    @Override
    public void run() {
        try {
            entry.request.setResultListener(this::complete);
            entry.request.run();
        }
        catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "API request failed before completion", ex);
        }
        finally {
            // Covers unchecked request errors and a return without a callback.
            complete(null, -1, null, -1);
        }
    }

    void rejected(RuntimeException ex) {
        LOGGER.log(Level.WARNING, "Unable to schedule API request", ex);
        complete(null, -1, null, -1);
    }

    private void complete(String result, int code, String error, int remaining) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        // Release the network slot before potentially expensive response processing.
        release.run();
        try {
            quota.accept(remaining);
            if (Debugging.isEnabled("requestresponse")) {
                if (result != null) {
                    LOGGER.info(result);
                }
                if (error != null) {
                    LOGGER.info("E:" + error);
                }
            }
            entry.listener.result(new ResultListener.Result(result, code, error));
        }
        catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "API response handler failed", ex);
        }
        finally {
            cleanup.run();
        }
    }
}
