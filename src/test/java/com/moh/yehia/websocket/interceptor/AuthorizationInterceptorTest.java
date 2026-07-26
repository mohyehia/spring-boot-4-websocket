package com.moh.yehia.websocket.interceptor;

import com.moh.yehia.websocket.metrics.WebsocketMetrics;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthorizationInterceptorTest {
    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private WebsocketMetrics websocketMetrics;

    @Mock
    private MessageChannel channel;

    private AuthorizationInterceptor authorizationInterceptor;

    @BeforeEach
    void setUp() {
        authorizationInterceptor = new AuthorizationInterceptor(jwtDecoder, websocketMetrics);
    }

    @Test
    void shouldAuthenticateWithValidJWT() {
        var headerAccessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        headerAccessor.setNativeHeader("Authorization", "Bearer token");
        headerAccessor.setLeaveMutable(true);

        var message = MessageBuilder.createMessage(new byte[0], headerAccessor.getMessageHeaders());

        var jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("moh")
                .claim("roles", List.of("USER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(jwtDecoder.decode(Mockito.any())).thenReturn(jwt);

        var result = authorizationInterceptor.preSend(message, channel);

        Assertions.assertThat(result).isNotNull();
        var resultAccessor = StompHeaderAccessor.wrap(result);
        Assertions.assertThat(resultAccessor.getUser()).isNotNull();
        Assertions.assertThat(resultAccessor.getUser().getName()).isEqualTo("moh");
    }

    @Test
    void shouldRejectMissingAuthHeader() {
        var accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThrows(IllegalArgumentException.class, () -> authorizationInterceptor.preSend(message, channel));
        verify(websocketMetrics).onAuthFailure();
    }

    @Test
    void shouldRejectMissingUserRole() {
        var accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer valid-token");
        accessor.setLeaveMutable(true);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        var jwt = Jwt.withTokenValue("valid-token")
                .header("alg", "HS256")
                .subject("moh")
                .claim("roles", List.of("VIEWER"))  // No USER role
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(jwtDecoder.decode("valid-token")).thenReturn(jwt);

        assertThrows(IllegalArgumentException.class, () -> authorizationInterceptor.preSend(message, channel));
        verify(websocketMetrics).onAuthFailure();
    }

    @Test
    void shouldPassThroughNonConnectMessages() {
        var accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setLeaveMutable(true);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        var result = authorizationInterceptor.preSend(message, channel);

        Assertions.assertThat(result).isNotNull();
        verifyNoInteractions(jwtDecoder);
    }
}