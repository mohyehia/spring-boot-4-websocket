package com.moh.yehia.websocket.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WebsocketMetrics {
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final Counter messagesReceived;
    private final Counter messagesSent;
    private final Counter connectionsRejected;
    private final Counter rateLimitHits;
    private final Counter authFailure;

    public WebsocketMetrics(MeterRegistry meterRegistry) {
        Gauge.builder("websocket.connections.active", activeConnections, AtomicInteger::get)
                .description("Number of active Websocket connections")
                .register(meterRegistry);

        messagesReceived = Counter.builder("websocket.messages.received")
                .description("Total messages received from clients")
                .register(meterRegistry);

        messagesSent = Counter.builder("websocket.messages.sent")
                .description("Total messages sent to clients")
                .register(meterRegistry);
        connectionsRejected = Counter.builder("websocket.connections.rejected")
                .description("Connections rejected (auth failure, session limit)")
                .register(meterRegistry);
        rateLimitHits = Counter.builder("websocket.ratelimit.hits")
                .description("Number of rate limit violations")
                .register(meterRegistry);
        authFailure = Counter.builder("websocket.auth.failure")
                .description("JWT authentication failure")
                .register(meterRegistry);
    }

    public void onConnect() {
        activeConnections.incrementAndGet();
    }

    public void onDisconnect() {
        activeConnections.decrementAndGet();
    }

    public void onMessageReceived() {
        messagesReceived.increment();
    }

    public void onMessageSent() {
        messagesSent.increment();
    }

    public void onConnectionRejected() {
        connectionsRejected.increment();
    }

    public void onRateLimitHits() {
        rateLimitHits.increment();
    }

    public void onAuthFailure() {
        authFailure.increment();
    }
}
