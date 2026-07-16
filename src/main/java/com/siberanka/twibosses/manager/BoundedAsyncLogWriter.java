package com.siberanka.twibosses.manager;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class BoundedAsyncLogWriter<T> implements AutoCloseable {
    private static final long POLL_TIMEOUT_MILLIS = 250L;
    private static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 2000L;

    private final ArrayBlockingQueue<T> queue;
    private final Sink<T> sink;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final Thread worker;

    BoundedAsyncLogWriter(String threadName, int capacity, Sink<T> sink) {
        this.queue = new ArrayBlockingQueue<>(Math.max(16, capacity));
        this.sink = Objects.requireNonNull(sink, "sink");
        this.worker = new Thread(this::runWorker, threadName);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    boolean submit(T entry) {
        if (entry == null || !this.accepting.get()) {
            return false;
        }
        if (this.queue.offer(entry)) {
            return true;
        }
        this.dropped.incrementAndGet();
        return false;
    }

    int pendingEntries() {
        return this.queue.size();
    }

    long failedWrites() {
        return this.failures.get();
    }

    boolean isAlive() {
        return this.worker.isAlive();
    }

    @Override
    public void close() {
        this.close(DEFAULT_CLOSE_TIMEOUT_MILLIS);
    }

    void close(long timeoutMillis) {
        if (!this.accepting.compareAndSet(true, false)) {
            return;
        }
        try {
            this.worker.join(Math.max(1L, timeoutMillis));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (this.worker.isAlive()) {
            this.queue.clear();
            this.worker.interrupt();
            try {
                this.worker.join(POLL_TIMEOUT_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runWorker() {
        while (this.accepting.get() || !this.queue.isEmpty()) {
            T entry;
            try {
                entry = this.queue.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                continue;
            }
            if (entry != null) {
                this.write(entry, this.dropped.getAndSet(0L));
            }
        }
        long remainingDropped = this.dropped.getAndSet(0L);
        if (remainingDropped > 0L) {
            this.write(null, remainingDropped);
        }
    }

    private void write(T entry, long droppedBefore) {
        try {
            this.sink.write(entry, droppedBefore);
        } catch (Throwable ignored) {
            this.failures.incrementAndGet();
        }
    }

    @FunctionalInterface
    interface Sink<T> {
        void write(T entry, long droppedBefore) throws Exception;
    }
}
