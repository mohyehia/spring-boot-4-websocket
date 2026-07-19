package com.moh.yehia.websocket.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ScheduledNotification {
    private static final Logger log = LoggerFactory.getLogger(ScheduledNotification.class);
    private final SimpMessagingTemplate simpMessagingTemplate;

    public ScheduledNotification(SimpMessagingTemplate simpMessagingTemplate) {
        this.simpMessagingTemplate = simpMessagingTemplate;
    }

//    @Scheduled(fixedRate = 5000)
//    public void sendNotification() {
//        log.info("Sending notification to Clients");
//        simpMessagingTemplate.convertAndSend("/topic/general-updates", "Sample updates for all clients");
//    }
}
