package com.moh.yehia.websocket.listener;

import com.moh.yehia.websocket.interceptor.UserSessionTracker;
import com.moh.yehia.websocket.metrics.WebsocketMetrics;
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
    private final WebsocketMetrics websocketMetrics;

    public WebSocketEventListener(UserSessionTracker userSessionTracker, WebsocketMetrics websocketMetrics) {
        this.userSessionTracker = userSessionTracker;
        this.websocketMetrics = websocketMetrics;
    }

    @EventListener
    void handleWebsocketConnectListener(SessionConnectedEvent event) {
        log.info("websocket connected to server: {}", event);
        websocketMetrics.onConnect();
    }

    @EventListener
    void handleWebsocketDisconnectListener(SessionDisconnectEvent event) {
        log.info("websocket disconnected from server: {}", event);
        var principal = event.getUser();
        if (principal == null) {
            return;
        }
        var username = principal.getName();
        var closeStatus = event.getCloseStatus();
        // Distinguish disconnect reasons
        switch (closeStatus.getCode()) {
            case 1000 -> log.info("User {} disconnected normally", username);
            case 1001 -> log.info("User {} navigated away", username);
            case 1002 -> log.warn("User {} disconnected: protocol error", username);
            case 1006 -> log.warn("User {} disconnected abnormally (network drop)", username);
            default -> log.info("User {} disconnected with code: {}", username, closeStatus);
        }

        userSessionTracker.removeSession(principal.getName());
        websocketMetrics.onDisconnect();

        // we can also have a logic here by for ex: Notify OTHER users (Do not try to message the disconnected client) as below:
        /*
        *   String broadcastPath = "/topic/public";
            DisconnectMessage message = new DisconnectMessage(username, "Disconnected gracefully");
            messagingTemplate.convertAndSend(broadcastPath, message);
        * */
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
