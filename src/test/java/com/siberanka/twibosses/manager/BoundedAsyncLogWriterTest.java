package com.siberanka.twibosses.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoundedAsyncLogWriterTest {
    @Test
    void drainsEntriesInOrderDuringClose() {
        List<Integer> written = Collections.synchronizedList(new ArrayList<>());
        BoundedAsyncLogWriter<Integer> writer = new BoundedAsyncLogWriter<>(
                "test-log-order", 16, (entry, dropped) -> written.add(entry));

        assertTrue(writer.submit(1));
        assertTrue(writer.submit(2));
        assertTrue(writer.submit(3));
        writer.close();

        assertEquals(List.of(1, 2, 3), written);
        assertEquals(0, writer.pendingEntries());
        assertFalse(writer.isAlive());
        assertFalse(writer.submit(4));
    }

    @Test
    void executesSinkAwayFromSubmittingThread() throws InterruptedException {
        Thread submittingThread = Thread.currentThread();
        AtomicReference<Thread> sinkThread = new AtomicReference<>();
        CountDownLatch written = new CountDownLatch(1);
        BoundedAsyncLogWriter<String> writer = new BoundedAsyncLogWriter<>(
                "test-log-thread", 16, (entry, dropped) -> {
                    sinkThread.set(Thread.currentThread());
                    written.countDown();
                });

        assertTrue(writer.submit("entry"));
        assertTrue(written.await(2, TimeUnit.SECONDS));
        writer.close();

        assertNotEquals(submittingThread, sinkThread.get());
        assertEquals("test-log-thread", sinkThread.get().getName());
    }

    @Test
    void dropsWithoutBlockingWhenBoundedQueueIsFull() throws InterruptedException {
        CountDownLatch sinkEntered = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        AtomicLong observedDropped = new AtomicLong();
        BoundedAsyncLogWriter<Integer> writer = new BoundedAsyncLogWriter<>(
                "test-log-backpressure", 16, (entry, dropped) -> {
                    observedDropped.accumulateAndGet(dropped, Math::max);
                    if (entry != null && entry == 0) {
                        sinkEntered.countDown();
                        releaseSink.await(2, TimeUnit.SECONDS);
                    }
                });

        assertTrue(writer.submit(0));
        assertTrue(sinkEntered.await(2, TimeUnit.SECONDS));
        for (int index = 1; index <= 16; index++) {
            assertTrue(writer.submit(index));
        }
        assertFalse(writer.submit(17));

        releaseSink.countDown();
        writer.close();

        assertTrue(observedDropped.get() >= 1L);
        assertEquals(0L, writer.failedWrites());
    }
}
