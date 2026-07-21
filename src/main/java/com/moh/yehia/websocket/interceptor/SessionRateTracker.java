package com.moh.yehia.websocket.interceptor;

import java.util.concurrent.atomic.AtomicInteger;

public class SessionRateTracker {

    private final AtomicInteger counter = new AtomicInteger(0);
    private volatile long windowStart = System.currentTimeMillis();
    private final int maxMessagesPerSecond;
    private final Object LOCK = new Object();

    public SessionRateTracker(int maxMessagesPerSecond) {
        this.maxMessagesPerSecond = maxMessagesPerSecond;
    }


    boolean allowMessage() {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (now - windowStart > 1000) {
                counter.set(1);
                windowStart = now;
                return true;
            }
        }
        return counter.incrementAndGet() <= maxMessagesPerSecond;
    }

}
