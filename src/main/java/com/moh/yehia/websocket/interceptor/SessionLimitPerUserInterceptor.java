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

@Component
public class SessionLimitPerUserInterceptor implements ChannelInterceptor {

    private final UserSessionTracker userSessionTracker;

    public SessionLimitPerUserInterceptor(UserSessionTracker userSessionTracker) {
        this.userSessionTracker = userSessionTracker;
    }

    @Override
    public @Nullable Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        var accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        var principal = accessor.getUser();
        if (principal == null) {
            return message;
        }

        var username = principal.getName();
        if (username == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            userSessionTracker.registerSession(username);
        }
        return message;
    }


}
