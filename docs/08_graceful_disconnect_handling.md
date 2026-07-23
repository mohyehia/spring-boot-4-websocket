# Graceful Disconnect Handling

## Overview

WebSocket connections can end in multiple ways:
- **Normal close** — client intentionally disconnects (tab close, logout)
- **Abnormal close** — network drop, server crash, client crash
- **Server-initiated** — rate limit, session limit, auth failure
- **Server shutdown** — deployment, restart

Each requires proper handling to avoid resource leaks and provide good user experience.

---

## Architecture

```
Disconnect Event Sources:
├── Client closes tab/browser       → code 1001 (Going Away)
├── Client calls stompClient.disconnect() → code 1000 (Normal)
├── Network drop                    → code 1006 (Abnormal Closure)
├── Server throws in interceptor    → code 1002 (Protocol Error)
└── Server shutdown                 → code 1001 (Going Away)

All trigger → SessionDisconnectEvent → WebSocketEventListener
```

---

## 1. Event Listener — Resource Cleanup

```java
package com.moh.yehia.websocket.listener;

import com.moh.yehia.websocket.interceptor.UserSessionTracker;
import com.moh.yehia.websocket.interceptor.MessagesPerSessionRateLimiterInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class WebSocketEventListener {
    private static final Logger log = LoggerFactory.getLogger(WebSocketEventListener.class);
    private final UserSessionTracker userSessionTracker;
    private final MessagesPerSessionRateLimiterInterceptor rateLimiterInterceptor;

    public WebSocketEventListener(UserSessionTracker userSessionTracker,
                                  MessagesPerSessionRateLimiterInterceptor rateLimiterInterceptor) {
        this.userSessionTracker = userSessionTracker;
        this.rateLimiterInterceptor = rateLimiterInterceptor;
    }

    @EventListener
    void handleWebsocketConnectListener(SessionConnectedEvent event) {
        var username = event.getUser() != null ? event.getUser().getName() : "unknown";
        log.info("User {} connected, sessionId: {}", username, event.getMessage().getHeaders().get("simpSessionId"));
    }

    @EventListener
    void handleWebsocketDisconnectListener(SessionDisconnectEvent event) {
        var closeStatus = event.getCloseStatus();
        var username = event.getUser() != null ? event.getUser().getName() : "unknown";
        var sessionId = event.getSessionId();

        // Log with close reason
        switch (closeStatus.getCode()) {
            case 1000 -> log.info("User {} disconnected normally (sessionId: {})", username, sessionId);
            case 1001 -> log.info("User {} navigated away (sessionId: {})", username, sessionId);
            case 1002 -> log.warn("User {} disconnected: protocol error (sessionId: {})", username, sessionId);
            case 1006 -> log.warn("User {} disconnected abnormally - network drop (sessionId: {})", username, sessionId);
            default -> log.info("User {} disconnected with code {} (sessionId: {})", username, closeStatus.getCode(), sessionId);
        }

        // Cleanup: session count
        if (event.getUser() != null) {
            userSessionTracker.removeSession(event.getUser().getName());
        }

        // Cleanup: rate limiter state
        rateLimiterInterceptor.removeSession(sessionId);
    }
}
```

---

## 2. Close Status Codes

| Code | Name                 | Meaning                                                  |
|------|----------------------|----------------------------------------------------------|
| 1000 | Normal Closure       | Client called `disconnect()` intentionally               |
| 1001 | Going Away           | Client navigating away, or server shutting down          |
| 1002 | Protocol Error       | Invalid STOMP frame, or exception in interceptor         |
| 1003 | Unsupported Data     | Message type not supported                               |
| 1006 | Abnormal Closure     | Connection dropped without close frame (network failure) |
| 1008 | Policy Violation     | Message violated server policy                           |
| 1011 | Unexpected Condition | Server encountered an unexpected error                   |

---

## 3. Graceful Server Shutdown

When the server restarts (deployment, scaling), active WebSocket connections should be drained — not killed instantly.

### `application.yaml`

```yaml
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

### What this does:

1. Server receives shutdown signal (SIGTERM from K8s, or manual stop)
2. Stops accepting **new** connections
3. Waits up to 30s for existing connections to complete
4. After timeout, forcefully closes remaining connections
5. Application exits

### Without graceful shutdown:

All WebSocket connections are **immediately killed** — clients receive no close frame, no error. They only detect the drop via heartbeat timeout (10-30s delay).

### With graceful shutdown:

Connected clients receive a proper close frame (code 1001 — Going Away), allowing the frontend to trigger immediate reconnection to another instance.

---

## 4. Resource Cleanup Checklist

On every disconnect, ensure these are cleaned up:

| Resource               | Where cleaned                       | How                             |
|------------------------|-------------------------------------|---------------------------------|
| Session count per user | `UserSessionTracker`                | `removeSession(username)`       |
| Rate limiter state     | `RateLimiterInterceptor`            | `removeSession(sessionId)`      |
| Custom session data    | Event listener                      | Clear any app-specific maps     |
| SecurityContext        | `SecurityContextChannelInterceptor` | `afterSendCompletion` clears it |

### Expose `removeSession` in rate limiter:

```java
// In MessagesPerSessionRateLimiterInterceptor or SessionRateTracker:
public void removeSession(String sessionId) {
    sessionRateMap.remove(sessionId);
}
```

---

## 5. Notify Other Users on Disconnect (Optional)

If your app needs to notify other connected users when someone leaves (e.g., chat, collaboration):

```java
@EventListener
void handleWebsocketDisconnectListener(SessionDisconnectEvent event) {
    var principal = event.getUser();
    if (principal != null) {
        String username = principal.getName();
        
        // Notify others via a public topic
        messagingTemplate.convertAndSend("/topic/user-status",
                Map.of("user", username, "status", "offline", "timestamp", System.currentTimeMillis())
        );
    }
}
```

Frontend subscribes to status updates:

```javascript
stompClient.subscribe('/topic/user-status', function(msg) {
    var status = JSON.parse(msg.body);
    console.log(status.user + ' is now ' + status.status);
    updateUserStatusUI(status);
});
```

---

## 6. Frontend — Handle Disconnects

```javascript
var stompClient;
var reconnectAttempts = 0;
var maxReconnectAttempts = 5;

function connect() {
    var socket = new SockJS('http://localhost:8080/ws');
    stompClient = Stomp.over(socket);

    stompClient.connect(
        {'Authorization': 'Bearer ' + token},
        function(frame) {
            reconnectAttempts = 0;  // Reset on successful connect
            console.log('Connected');
            stompClient.subscribe('/user/queue/notifications', onMessage);
            stompClient.subscribe('/user/queue/errors', onError);
        },
        function(errorFrame) {
            console.error('Disconnected:', errorFrame);
            handleReconnect();
        }
    );
}

function handleReconnect() {
    if (reconnectAttempts >= maxReconnectAttempts) {
        showNotification('Unable to reconnect. Please refresh the page.');
        return;
    }
    reconnectAttempts++;
    var delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 30000); // 2s, 4s, 8s, 16s, 30s max
    showNotification('Reconnecting in ' + (delay / 1000) + 's...');
    setTimeout(connect, delay);
}
```

---

## 7. K8s / Deployment Considerations

For zero-downtime deployments with WebSocket:

### Pod `preStop` hook:

```yaml
spec:
  terminationGracePeriodSeconds: 45
  containers:
    - lifecycle:
        preStop:
          exec:
            command: ["sh", "-c", "sleep 5"]
```

### Flow during rolling update:

```
1. K8s sends SIGTERM to old pod
2. preStop: sleep 5s (allows LB to deregister the pod)
3. Spring receives shutdown signal → stops accepting new connections
4. Existing WebSocket clients stay connected for up to 30s
5. Clients receive close frame → frontend reconnects to new pod
6. After 30s timeout → force close remaining connections
7. Pod terminates
```

### Ingress configuration:

Ensure your ingress `proxy-read-timeout` is long enough for the drain period but short enough to detect truly dead connections:

```yaml
annotations:
  nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
```

---

## Key Takeaways

1. **`SessionDisconnectEvent` always fires** — even on abrupt disconnects. Use it as the single cleanup point.
2. **Graceful shutdown** (`server.shutdown: graceful`) is essential for production — prevents instant kill of all connections during deployments.
3. **Cleanup all per-session state** — session counts, rate limiter maps, any custom tracking.
4. **Frontend must handle reconnection** — the server cannot notify a disconnected client. Exponential backoff prevents thundering herd.
5. **Close status codes** are useful for debugging and monitoring — log them.

