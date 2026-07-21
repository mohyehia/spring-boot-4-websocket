package com.moh.yehia.websocket.interceptor;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 4. Rate Limiting (messages per second)
 * What: Limits how many messages a single client can send per second.
 * Why: Without it, a single client can send thousands of messages per second, overwhelming your @MessageMapping handlers, database, or downstream services.
 * Even an authenticated user could accidentally (or intentionally) flood your server.
 * */
@Component
public class MessagesPerSessionRateLimiterInterceptor implements ChannelInterceptor {
    private static final int MAX_MESSAGES_PER_SECOND = 5;
    private final Map<String, SessionRateTracker> sessionRateTrackerMap = new ConcurrentHashMap<>();

    @Override
    public @Nullable Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        var accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.SEND.equals(accessor.getCommand())) {
            var sessionId = accessor.getSessionId();
            if (sessionId == null) {
                return message;
            }
            // logic for computing the rateTracker
            SessionRateTracker sessionRateTracker = sessionRateTrackerMap.computeIfAbsent(sessionId, k -> new SessionRateTracker(MAX_MESSAGES_PER_SECOND));
            if (!sessionRateTracker.allowMessage()) {
                throw new IllegalStateException("Rate limit exceeded: max " + MAX_MESSAGES_PER_SECOND + " messages/second");
            }
        }

        // cleanup on disconnect
        if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            var sessionId = accessor.getSessionId();
            if (sessionId != null) {
                // remove from hashmap
                sessionRateTrackerMap.remove(sessionId);
            }
        }
        return message;
    }
}
