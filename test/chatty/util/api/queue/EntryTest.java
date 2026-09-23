package chatty.util.api.queue;

import java.util.concurrent.PriorityBlockingQueue;
import org.junit.Test;
import static org.junit.Assert.*;

public class EntryTest {
    @Test
    public void equalPriorityIsFifoEvenWhenMoreWorkArrives() {
        PriorityBlockingQueue<Entry> queue = new PriorityBlockingQueue<>();
        Entry first = entry(1, "first");
        Entry second = entry(1, "second");
        queue.add(second);
        queue.add(first);
        assertSame(first, queue.poll());
        queue.add(entry(1, "third"));
        assertSame(second, queue.poll());
    }

    @Test
    public void explicitPriorityStillTakesPrecedenceOverAge() {
        PriorityBlockingQueue<Entry> queue = new PriorityBlockingQueue<>();
        Entry background = entry(2, "background");
        Entry foreground = entry(1, "foreground");
        queue.add(background);
        queue.add(foreground);
        assertSame(foreground, queue.poll());
        assertSame(background, queue.poll());
    }

    private Entry entry(int priority, String name) {
        return new Entry(priority, new Request("https://example.invalid/" + name), result -> {});
    }
}
