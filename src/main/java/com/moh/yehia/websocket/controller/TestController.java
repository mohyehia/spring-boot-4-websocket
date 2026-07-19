package com.moh.yehia.websocket.controller;

import com.moh.yehia.websocket.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
public class TestController {
    private static final Logger log = LoggerFactory.getLogger(TestController.class);
    private final NotificationService notificationService;

    public TestController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @MessageMapping("/notification")
        // FE will need to call this endpoint using /app/notification
    void sendNotification(@Payload String message, SimpMessageHeaderAccessor simpMessageHeaderAccessor, Principal principal) throws InterruptedException {
        log.info("Received notification message: {}", message);
        log.info("simpMessageHeaderAccessor: {}", simpMessageHeaderAccessor);
        log.info("principal username: {}", principal.getName());

        notificationService.sendNotification(message, simpMessageHeaderAccessor, principal.getName());
    }
}
