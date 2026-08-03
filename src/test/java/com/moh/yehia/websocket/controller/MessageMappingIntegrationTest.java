package com.moh.yehia.websocket.controller;

import com.moh.yehia.websocket.exception.ErrorDTO;
import com.moh.yehia.websocket.helper.TestJwtHelper;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "websocket.broker.type=simple")
class MessageMappingIntegrationTest {
    @LocalServerPort
    private int port;

    private WebSocketStompClient stompClient;
    private String wsUrl;

    @BeforeEach
    void setUp() {
        wsUrl = "ws://localhost:" + port + "/ws";
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new org.springframework.messaging.converter.CompositeMessageConverter(List.of(
                new StringMessageConverter(),
                new org.springframework.messaging.converter.JacksonJsonMessageConverter()
        )));
    }

    @Test
    void shouldSendAndReceiveNotification() throws Exception {
        var session = connectWithUser("moh");
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();

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

        Awaitility.waitAtMost(500, TimeUnit.MILLISECONDS); // Wait for subscription to register

        session.send("/app/notification", "{\"content\":\"hello world\"}");

        String response = messages.poll(5, TimeUnit.SECONDS);
        Assertions.assertThat(response).isNotNull()
                .isNotEmpty()
                .contains("hello world");
        session.disconnect();
    }

    @Test
    void shouldReceiveErrorForInvalidMessage() throws Exception {
        var session = connectWithUser("moh");
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();

        session.subscribe("/user/queue/errors", new StompFrameHandler() {
            @Override
            public @NonNull Type getPayloadType(@NonNull StompHeaders headers) {
                return ErrorDTO.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                ErrorDTO error = (ErrorDTO) payload;
                errors.add(error.message());
            }
        });

        Awaitility.waitAtMost(1000, TimeUnit.MILLISECONDS);

        // Send invalid message (e.g., too short — depends on your validation)
        session.send("/app/notification", "hi");

        String error = errors.poll(5, TimeUnit.SECONDS);
        Assertions.assertThat(error).isNotNull()
                .isNotEmpty();

        session.disconnect();
    }

    @Test
    void shouldNotReceiveOtherUsersNotifications() throws Exception {
        var sessionMoh = connectWithUser("moh");
        var sessionAhmed = connectWithUser("ahmed");

        BlockingQueue<String> mohMessages = new LinkedBlockingQueue<>();
        BlockingQueue<String> ahmedMessages = new LinkedBlockingQueue<>();

        sessionMoh.subscribe("/user/queue/notifications", new StompFrameHandler() {
            @Override
            public @NonNull Type getPayloadType(@NonNull StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                mohMessages.add((String) payload);
            }
        });

        sessionAhmed.subscribe("/user/queue/notifications", new StompFrameHandler() {
            @Override
            public @NonNull Type getPayloadType(@NonNull StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                ahmedMessages.add((String) payload);
            }
        });

        Awaitility.waitAtMost(500, TimeUnit.MILLISECONDS);

        // Moh sends a message — only moh should get the response
        sessionMoh.send("/app/notification", """
                {
                    "content": "moh's message"
                }
                """);

        String mohResponse = mohMessages.poll(5, TimeUnit.SECONDS);
        Assertions.assertThat(mohResponse).isNotNull();

        String ahmedResponse = ahmedMessages.poll(2, TimeUnit.SECONDS);
        Assertions.assertThat(ahmedResponse).isNull();

        sessionMoh.disconnect();
        sessionAhmed.disconnect();
    }


    private StompSession connectWithUser(String username) throws Exception {
        String jwt = TestJwtHelper.generateToken(username, List.of("USER"));
        var headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + jwt);
        return stompClient.connectAsync(wsUrl, new WebSocketHttpHeaders(), headers, new TestSessionHandler())
                .get(5, TimeUnit.SECONDS);
    }

    private static class TestSessionHandler extends StompSessionHandlerAdapter {
        @Override
        public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
        }
    }
}
