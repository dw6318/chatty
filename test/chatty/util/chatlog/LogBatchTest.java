package chatty.util.chatlog;

import chatty.util.chatlog.LogWriter.LogItem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Test directories are retained; these tests never delete files. */
public class LogBatchTest {
    @Test
    public void smallWritesStayBufferedUntilFlushOrClose() throws Exception {
        Path dir = Files.createTempDirectory("chatty-log-buffer-test-");
        LogFile file = LogFile.get(dir, "channel", false);
        assertNotNull(file);
        try {
            assertTrue(file.write("first"));
            assertTrue(file.write("second \uD83D\uDE00"));
            assertEquals(0, Files.size(dir.resolve("channel.log")));
            assertTrue(file.flush());
            assertEquals(Arrays.asList("first", "second \uD83D\uDE00"), lines(dir, "channel"));
            assertTrue(file.flush());
            assertTrue(file.write("third"));
        }
        finally {
            file.close();
        }
        assertEquals(Arrays.asList("first", "second \uD83D\uDE00", "third"), lines(dir, "channel"));
    }

    @Test
    public void batchesPreserveOrderAcrossChannelsBroadcastsAndClose() throws Exception {
        Path dir = Files.createTempDirectory("chatty-log-order-test-");
        BlockingQueue<LogItem> queue = new LinkedBlockingQueue<>();
        List<String> expectedA = new ArrayList<>();
        List<String> expectedB = new ArrayList<>();
        for (int i = 0; i < 180; i++) {
            String line = "line-" + i;
            String channel = i % 2 == 0 ? "a" : "b";
            queue.add(new LogItem(channel, line));
            (i % 2 == 0 ? expectedA : expectedB).add(line);
        }
        queue.add(new LogItem(null, "broadcast"));
        expectedA.add("broadcast");
        expectedB.add("broadcast");
        queue.add(new LogItem("a", null));
        queue.add(new LogItem("a", "after-reopen"));
        expectedA.add("after-reopen");
        queue.add(new LogItem(null, null));
        new LogWriter(queue, dir, "never", false, false).run();
        assertEquals(expectedA, messageLines(dir, "a"));
        assertEquals(expectedB, messageLines(dir, "b"));
    }

    @Test(timeout = 10000)
    public void idleWriterFlushesWithoutWaitingForAnotherMessage() throws Exception {
        Path dir = Files.createTempDirectory("chatty-log-idle-test-");
        BlockingQueue<LogItem> queue = new LinkedBlockingQueue<>();
        Thread worker = new Thread(new LogWriter(queue, dir, "never", false, false));
        worker.start();
        try {
            queue.add(new LogItem("channel", "visible-while-running"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean visible = false;
            while (!visible && System.nanoTime() < deadline) {
                Path path = dir.resolve("channel.log");
                visible = Files.exists(path) && lines(dir, "channel").contains("visible-while-running");
                if (!visible) {
                    Thread.sleep(10);
                }
            }
            assertTrue("A quiet channel's buffered line must flush without another event", visible);
            assertTrue(worker.isAlive());
        }
        finally {
            queue.add(new LogItem(null, null));
            worker.join(3000);
            if (worker.isAlive()) {
                worker.interrupt();
                worker.join(1000);
            }
        }
        assertFalse(worker.isAlive());
        assertTrue(lines(dir, "channel").stream().anyMatch(s -> s.startsWith("# Log closed:")));
    }

    @Test(timeout = 10000)
    public void interruptionStillFlushesAlreadyBufferedLines() throws Exception {
        Path dir = Files.createTempDirectory("chatty-log-interrupt-test-");
        BlockingQueue<LogItem> queue = new LinkedBlockingQueue<>();
        Thread worker = new Thread(new LogWriter(queue, dir, "never", false, false));
        worker.start();
        queue.add(new LogItem("channel", "before-interrupt"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        // Waiting on a timed queue poll means the writer has finished buffering.
        while (worker.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        worker.interrupt();
        worker.join(3000);
        assertFalse(worker.isAlive());
        assertTrue(lines(dir, "channel").contains("before-interrupt"));
    }

    private List<String> lines(Path dir, String name) throws Exception {
        return Files.readAllLines(dir.resolve(name + ".log"), StandardCharsets.UTF_8);
    }

    private List<String> messageLines(Path dir, String name) throws Exception {
        List<String> result = new ArrayList<>();
        for (String line : lines(dir, name)) {
            if (!line.startsWith("#") && !line.equals("-")) {
                result.add(line);
            }
        }
        return result;
    }
}
