package com.moh.yehia.websocket.config;

import com.moh.yehia.websocket.helper.TestJwtHelper;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"websocket.allows-origins=*", "websocket.broker.type=simple"})
class WebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    private WebSocketStompClient stompClient;
    private String wsUrl;

    @BeforeEach
    void setUp() {
        wsUrl = "ws://localhost:" + port + "/ws";
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new StringMessageConverter());
    }

    private StompHeaders connectHeaders(String username) {
        var headers = new StompHeaders();
        String token = TestJwtHelper.generateToken(username, List.of("USER"));
        headers.add("Authorization", "Bearer " + token);
        return headers;
    }

    @Test
    void shouldConnectWithValidJwt() throws Exception {
        var headers = connectHeaders("connectuser");

        var session = stompClient.connectAsync(wsUrl, new WebSocketHttpHeaders(), headers, new TestSessionHandler())
                .get(5, TimeUnit.SECONDS);

        assertTrue(session.isConnected());
        session.disconnect();
    }

    @Test
    void shouldRejectWithoutJwt() {
        assertThrows(Exception.class, () ->
                stompClient.connectAsync(wsUrl, new TestSessionHandler())
                        .get(5, TimeUnit.SECONDS)
        );
    }

    @Test
    void shouldReceivePrivateNotification() throws Exception {
        var headers = connectHeaders("notifyuser");

        BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        var session = stompClient.connectAsync(wsUrl, new WebSocketHttpHeaders(), headers, new TestSessionHandler())
                .get(5, TimeUnit.SECONDS);

        session.subscribe("/user/queue/notifications", new StompFrameHandler() {
            @Override
            public @NonNull Type getPayloadType(@NonNull StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                messages.add((String) payload);
            }
        });

        // Message must be > 5 chars to pass TestController validation
        session.send("/app/notification", "hello world");

        // Wait for response — NotificationService has a 10s internal delay
        String response = messages.poll(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive a notification back");
        assertTrue(response.contains("hello world"));

        session.disconnect();
    }

    @Test
    void shouldEnforceRateLimit() throws Exception {
        var headers = connectHeaders("ratelimituser");

        CountDownLatch disconnectLatch = new CountDownLatch(1);

        var session = stompClient.connectAsync(wsUrl, new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {
            @Override
            public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
                disconnectLatch.countDown();
            }
        }).get(5, TimeUnit.SECONDS);

        // Send more messages than rate limit allows (max 5/sec).
        // Using a non-existent destination: the rate limiter intercepts in preSend()
        // BEFORE message routing, so it triggers regardless of whether a @MessageMapping exists.
        // This avoids blocking handler threads (no controller with Thread.sleep is invoked).
        for (int i = 0; i < 10; i++) {
            session.send("/app/ratelimit-test", "message-" + i);
        }

        // The rate limiter throws IllegalStateException after 5 messages/sec.
        // This triggers the StompSubProtocolErrorHandler which sends an ERROR frame,
        // causing the server to close the connection.
        boolean disconnected = disconnectLatch.await(5, TimeUnit.SECONDS);
        assertTrue(disconnected, "Session should be disconnected after rate limit breach");
    }

    @Test
    void shouldEnforceSessionLimit() throws Exception {
        var headers = connectHeaders("sessionlimituser");

        // Open max allowed sessions (2)
        var session1 = stompClient.connectAsync(wsUrl, new WebSocketHttpHeaders(), headers, new TestSessionHandler())
                .get(5, TimeUnit.SECONDS);
        var session2 = stompClient.connectAsync(wsUrl, new WebSocketHttpHeaders(), headers, new TestSessionHandler())
                .get(5, TimeUnit.SECONDS);

        assertTrue(session1.isConnected());
        assertTrue(session2.isConnected());

        // 3rd session should fail — session limit is 2 per user
        assertThrows(Exception.class, () ->
                stompClient.connectAsync(wsUrl, new WebSocketHttpHeaders(), headers, new TestSessionHandler())
                        .get(5, TimeUnit.SECONDS)
        );

        session1.disconnect();
        session2.disconnect();
    }

    private static class TestSessionHandler extends StompSessionHandlerAdapter {
        @Override
        public void handleException(@NonNull StompSession session, StompCommand command,
                                    @NonNull StompHeaders headers, byte @NonNull [] payload, @NonNull Throwable exception) {
        }

        @Override
        public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
        }
    }
}