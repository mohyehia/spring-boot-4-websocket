package com.moh.yehia.websocket.controller;

import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.config.WebSocketMessageBrokerStats;

import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/admin")
public class WebsocketStatsController {
    private final WebSocketMessageBrokerStats webSocketMessageBrokerStats;
    private final SimpUserRegistry simpUserRegistry;

    public WebsocketStatsController(WebSocketMessageBrokerStats webSocketMessageBrokerStats, SimpUserRegistry simpUserRegistry) {
        this.webSocketMessageBrokerStats = webSocketMessageBrokerStats;
        this.simpUserRegistry = simpUserRegistry;
    }


    @GetMapping("/ws-stats")
    Map<String, Object> getWebSocketStats() {
        return Map.of(
                "sessionStats", Objects.requireNonNull(webSocketMessageBrokerStats.getWebSocketSessionStats()),
                "totalConnectedUsers", simpUserRegistry.getUserCount(),
                "connectedUsers", simpUserRegistry.getUsers().stream()
                        .map(u -> Map.of(
                                "username", u.getName(),
                                "activeSessions", u.getSessions().size()
                        )).toList(),
                "stompSubProtocolStats", Objects.requireNonNull(webSocketMessageBrokerStats.getStompSubProtocolStats()),
                "stompBrokerRelayStats", Objects.requireNonNull(webSocketMessageBrokerStats.getStompBrokerRelayStats()),
                "inboundChannelStats", webSocketMessageBrokerStats.getClientInboundExecutorStatsInfo(),
                "outboundChannelStats", webSocketMessageBrokerStats.getClientOutboundExecutorStatsInfo()
        );
    }

}
