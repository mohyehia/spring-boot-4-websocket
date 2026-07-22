package com.moh.yehia.websocket.listener;

import com.moh.yehia.websocket.interceptor.UserSessionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

@Component
public class WebSocketEventListener {
    private static final Logger log = LoggerFactory.getLogger(WebSocketEventListener.class);
    private final UserSessionTracker userSessionTracker;

    public WebSocketEventListener(UserSessionTracker userSessionTracker) {
        this.userSessionTracker = userSessionTracker;
    }

    @EventListener
    void handleWebsocketConnectListener(SessionConnectedEvent event) {
        log.info("websocket connected to server: {}", event);
    }

    @EventListener
    void handleWebsocketDisconnectListener(SessionDisconnectEvent event) {
        log.info("websocket disconnected from server: {}", event);
        var principal = event.getUser();
        if (principal == null) {
            return;
        }
        userSessionTracker.removeSession(principal.getName());
    }

    @EventListener
    void handleSubscribeEvent(SessionSubscribeEvent event) {
        log.info("subscribe to server: {}", event);
    }

    @EventListener
    void handleUnsubscribeEvent(SessionUnsubscribeEvent event) {
        log.info("unsubscribe to server: {}", event);
    }
}
