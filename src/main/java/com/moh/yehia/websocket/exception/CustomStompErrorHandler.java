package com.moh.yehia.websocket.exception;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

import java.nio.charset.StandardCharsets;

@Component
public class CustomStompErrorHandler extends StompSubProtocolErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(CustomStompErrorHandler.class);

    @Override
    public @Nullable Message<byte[]> handleClientMessageProcessingError(@Nullable Message<byte[]> clientMessage, @NonNull Throwable ex) {
        log.error("STOMP client message processing error: {}", ex.getMessage(), ex);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(getErrorMessage(ex));
        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(getErrorBody(ex).getBytes(StandardCharsets.UTF_8), accessor.getMessageHeaders());
    }

    private String getErrorMessage(Throwable ex) {
        if (ex.getCause() != null) {
            Throwable cause = ex.getCause();
            if (cause instanceof IllegalArgumentException) {
                return "Authentication failed";
            }
            if (cause instanceof IllegalStateException) {
                return cause.getMessage(); // Rate limit, session limit
            }
            if (cause instanceof MessageDeliveryException) {
                return cause.getMessage();
            }
        }
        return "Server error";
    }

    private String getErrorBody(Throwable ex) {
        if (ex.getCause() != null) {
            Throwable cause = ex.getCause();
            if (cause instanceof IllegalArgumentException) {
                return "Authentication failed: " + cause.getMessage();
            }
            if (cause instanceof IllegalStateException) {
                return cause.getMessage();
            }
        }
        return "An unexpected error occurred";
    }
}
