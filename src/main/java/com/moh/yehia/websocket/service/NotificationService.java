package com.moh.yehia.websocket.service;

import com.moh.yehia.websocket.metrics.WebsocketMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private final SimpMessagingTemplate simpMessagingTemplate;
    private final WebsocketMetrics websocketMetrics;

    public NotificationService(SimpMessagingTemplate simpMessagingTemplate, WebsocketMetrics websocketMetrics) {
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.websocketMetrics = websocketMetrics;
    }

    public void sendNotification(String message, SimpMessageHeaderAccessor simpMessageHeaderAccessor, String username) throws InterruptedException {
        String sessionId = simpMessageHeaderAccessor.getSessionId();
        log.info("sessionId: {}", sessionId);
        assert sessionId != null;


        var newHeaderAccessors = SimpMessageHeaderAccessor.create();
        newHeaderAccessors.setSessionId(sessionId);
        newHeaderAccessors.setLeaveMutable(true);
        log.info("Sending new message to the same client!");
        Thread.sleep(10_000);

        Map<String, Object> sessionAttributes = simpMessageHeaderAccessor.getSessionAttributes();
        assert sessionAttributes != null;
        sessionAttributes.forEach((key, value) -> log.info("key: {}, value: {}", key, value));
        simpMessagingTemplate.convertAndSendToUser(
                username,
                "/queue/notifications", // FE client need to subscribe to /user/queue/notifications
                "Received this message from you: " + message,
                newHeaderAccessors.getMessageHeaders()
        );
        websocketMetrics.onMessageSent();
    }
}
