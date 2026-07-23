package com.moh.yehia.websocket.interceptor;

import com.moh.yehia.websocket.metrics.WebsocketMetrics;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class UserSessionTracker {
    private static final int MAX_SESSIONS_PER_USER = 2;
    private final Map<String, AtomicInteger> userSessionCountMap = new ConcurrentHashMap<>();
    private final WebsocketMetrics websocketMetrics;

    public UserSessionTracker(WebsocketMetrics websocketMetrics) {
        this.websocketMetrics = websocketMetrics;
    }

    public void registerSession(String username) {
        var count = userSessionCountMap.computeIfAbsent(username, _ -> new AtomicInteger(0));
        if (count.incrementAndGet() > MAX_SESSIONS_PER_USER) {
            count.decrementAndGet();
            websocketMetrics.onConnectionRejected();
            throw new IllegalStateException("Max concurrent sessions (" + MAX_SESSIONS_PER_USER + ") exceeded for user: " + username);
        }
    }

    public void removeSession(String username) {
        if (username == null) {
            return;
        }
        var count = userSessionCountMap.get(username);
        if (count != null && count.decrementAndGet() <= 0) {
            userSessionCountMap.remove(username);
        }
    }
}
