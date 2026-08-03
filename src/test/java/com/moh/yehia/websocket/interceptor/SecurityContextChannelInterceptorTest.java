package com.moh.yehia.websocket.interceptor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SecurityContextChannelInterceptorTest {
    private SecurityContextChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new SecurityContextChannelInterceptor();
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldSetSecurityContextFromPrincipal() {
        var auth = new UsernamePasswordAuthenticationToken("moh", null);
        var accessor = SimpMessageHeaderAccessor.create();
        accessor.setUser(auth);
        accessor.setLeaveMutable(true);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.preSend(message, mock(org.springframework.messaging.MessageChannel.class));

        assertEquals(auth, SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void shouldClearSecurityContextAfterCompletion() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("moh", null));

        var accessor = SimpMessageHeaderAccessor.create();
        accessor.setLeaveMutable(true);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.afterSendCompletion(message, mock(org.springframework.messaging.MessageChannel.class), true, null);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void shouldHandleNullPrincipal() {
        var accessor = SimpMessageHeaderAccessor.create();
        accessor.setLeaveMutable(true);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertDoesNotThrow(() -> interceptor.preSend(message, mock(org.springframework.messaging.MessageChannel.class)));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
