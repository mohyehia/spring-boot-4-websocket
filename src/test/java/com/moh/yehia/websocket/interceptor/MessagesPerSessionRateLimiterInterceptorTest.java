package com.moh.yehia.websocket.interceptor;

import com.moh.yehia.websocket.metrics.WebsocketMetrics;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MessagesPerSessionRateLimiterInterceptorTest {
    @Mock
    private WebsocketMetrics websocketMetrics;
    @Mock
    private MessageChannel channel;

    private MessagesPerSessionRateLimiterInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new MessagesPerSessionRateLimiterInterceptor(websocketMetrics);
    }

    @Test
    void shouldAllowMessagesWithinLimit() {
        var message = createSendMessage("session-1");

        var result = interceptor.preSend(message, channel);

        Assertions.assertThat(result).isNotNull();
        verify(websocketMetrics).onMessageReceived();
    }

    @Test
    void shouldDenyMessagesExceedingLimit() {
        // Send messages up to and beyond limit
        for (int i = 0; i < 5; i++) {
            interceptor.preSend(createSendMessage("session-1"), channel);
        }

        assertThrows(IllegalStateException.class,
                () -> interceptor.preSend(createSendMessage("session-1"), channel));
        verify(websocketMetrics).onRateLimitHits();
    }

    @Test
    void shouldTrackSessionsIndependently() {
        for (int i = 0; i < 5; i++) {
            interceptor.preSend(createSendMessage("session-1"), channel);
        }
        // session-2 should still be allowed
        assertDoesNotThrow(() -> interceptor.preSend(createSendMessage("session-2"), channel));
    }

    @Test
    void shouldCleanUpOnDisconnect() {
        interceptor.preSend(createSendMessage("session-1"), channel);

        var disconnectAccessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        disconnectAccessor.setSessionId("session-1");
        disconnectAccessor.setLeaveMutable(true);
        var disconnectMessage = MessageBuilder.createMessage(new byte[0], disconnectAccessor.getMessageHeaders());

        interceptor.preSend(disconnectMessage, channel);

        // After cleanup, should be able to send again (new session with same ID)
        assertDoesNotThrow(() -> interceptor.preSend(createSendMessage("session-1"), channel));
    }

    @Test
    void shouldPassThroughNonSendMessages() {
        var accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("session-1");
        accessor.setLeaveMutable(true);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        var result = interceptor.preSend(message, channel);

        Assertions.assertThat(result).isNotNull();
        verify(websocketMetrics, never()).onMessageReceived();
    }


    private Message<?> createSendMessage(String sessionId) {
        var accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId(sessionId);
        accessor.setDestination("/app/notification");
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
