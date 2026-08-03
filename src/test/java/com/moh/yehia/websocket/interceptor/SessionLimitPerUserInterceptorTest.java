package com.moh.yehia.websocket.interceptor;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SessionLimitPerUserInterceptorTest {
    @Mock
    private UserSessionTracker userSessionTracker;
    @Mock
    private MessageChannel channel;

    private SessionLimitPerUserInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new SessionLimitPerUserInterceptor(userSessionTracker);
    }

    @Test
    void shouldDelegateToTrackerOnConnect() {
        var message = createMessage(StompCommand.CONNECT);

        interceptor.preSend(message, channel);

        verify(userSessionTracker).registerSession("moh");
    }

    @Test
    void shouldPassThroughNonConnectMessages() {
        var message = createMessage(StompCommand.SEND);

        var result = interceptor.preSend(message, channel);

        Assertions.assertThat(result).isNotNull();
    }

    @Test
    void shouldPassThroughWhenNoPrincipal() {
        var accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        var result = interceptor.preSend(message, channel);

        Assertions.assertThat(result).isNotNull();
    }

    private Message<?> createMessage(StompCommand command) {
        var accessor = StompHeaderAccessor.create(command);
        accessor.setUser(new UsernamePasswordAuthenticationToken("moh", null));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
