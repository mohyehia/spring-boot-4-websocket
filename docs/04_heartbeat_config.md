# WebSocket Heartbeat Configuration

## Purpose

Heartbeats detect **dead connections**. Without them, if a client silently disconnects (e.g., network drop, laptop lid closed, mobile app backgrounded), the server won't know until a TCP timeout — which can take **minutes**.

With heartbeats, dead connections are detected within ~30 seconds.

---

## Configuration

### Server-side (`WebSocketConfig.java`)

```java
@Override
public void configureMessageBroker(MessageBrokerRegistry messageBrokerRegistry) {
    messageBrokerRegistry.enableSimpleBroker("/queue", "/topic")
            .setHeartbeatValue(new long[]{10000, 10000})  // [server-send, server-receive] in ms
            .setTaskScheduler(heartbeatScheduler());
    messageBrokerRegistry.setApplicationDestinationPrefixes("/app");
    messageBrokerRegistry.setUserDestinationPrefix("/user");
}
```

### TaskScheduler Bean (required)

```java
@Bean
public TaskScheduler heartbeatScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("ws-heartbeat-");
    scheduler.initialize();
    return scheduler;
}
```

> **Important:** The `TaskScheduler` is **required**. Without it, heartbeat values are configured but never actually sent — Spring needs a scheduler to trigger periodic heartbeat writes.

---

## How It Works

### Heartbeat Values

`setHeartbeatValue(new long[]{10000, 10000})`:

| Index | Meaning                    | Value      | Description                                                      |
|-------|----------------------------|------------|------------------------------------------------------------------|
| `[0]` | Server-send interval       | `10000` ms | Server sends a heartbeat every 10s (if no other message is sent) |
| `[1]` | Server-receive expectation | `10000` ms | Server expects a heartbeat from client every 10s                 |

### STOMP Heartbeat Negotiation

During the STOMP `CONNECT`/`CONNECTED` handshake, the client and server exchange their heartbeat preferences:

```
Client CONNECT:
heart-beat:10000,10000    → "I'll send every 10s, I expect every 10s"

Server CONNECTED:
heart-beat:10000,10000    → "I'll send every 10s, I expect every 10s"
```

The actual heartbeat interval is the **maximum** of both sides' values:
- `actual_send = max(server_send, client_receive)`
- `actual_receive = max(server_receive, client_send)`

### Dead Connection Detection

If the server doesn't receive anything from the client within approximately **3× the expected interval** (implementation-dependent), it considers the connection dead and triggers a `SessionDisconnectEvent`.

With 10s heartbeats → dead connection detected in ~30s.

---

## Client-side Configuration

### STOMP.js / SockJS

```javascript
var stompClient = Stomp.over(socket);

// Configure heartbeat intervals (milliseconds)
stompClient.heartbeat.outgoing = 10000; // Client sends PING every 10s
stompClient.heartbeat.incoming = 10000; // Client expects PONG every 10s
```

Most STOMP.js clients send heartbeats **by default** (10s is typical).

### Expected Browser Console Logs

```
CONNECTED
version:1.1
heart-beat:10000,10000
user-name:moh

send PING every 10000ms
check PONG every 10000ms

>>> PING
<<< PONG
>>> PING
<<< PONG
```

---

## Server-side Logging

Heartbeat frames (PING/PONG) are handled at the **transport layer** — they do **not** pass through channel interceptors or application code. They're raw STOMP heartbeat bytes (`\n`) exchanged at the WebSocket transport level.

### To see heartbeat activity in server logs (debug only)

```yaml
logging:
  level:
    org.springframework.web.socket: DEBUG
    org.springframework.web.socket.messaging.StompSubProtocolHandler: TRACE
    org.springframework.web.socket.sockjs.transport: DEBUG
```

> ⚠️ **Only use these for debugging** — they produce very verbose output in production.

---

## What Happens on Dead Connection

When a heartbeat timeout is detected:

1. Server detects no activity from client within timeout window
2. `SessionDisconnectEvent` is fired
3. `WebSocketEventListener` handles the disconnect
4. User's session is cleaned up
5. Any session-scoped resources are released

```java
@EventListener
public void handleSessionDisconnect(SessionDisconnectEvent event) {
    // CloseStatus will indicate the reason
    log.info("Disconnected: sessionId={}, status={}", 
             event.getSessionId(), event.getCloseStatus());
}
```

---

## Recommended Values

| Environment    | Server-send   | Server-receive | Notes                                      |
|----------------|---------------|----------------|--------------------------------------------|
| Development    | `10000` (10s) | `10000` (10s)  | Quick detection for testing                |
| Production     | `10000` (10s) | `10000` (10s)  | Good balance of responsiveness vs overhead |
| Mobile clients | `20000` (20s) | `20000` (20s)  | Reduces battery drain                      |
| High-traffic   | `25000` (25s) | `25000` (25s)  | Reduces heartbeat message overhead         |

### Considerations

- **Lower values** → faster dead connection detection, but more network traffic
- **Higher values** → less overhead, but slower detection of dead connections
- **Mobile/battery** → use higher values to reduce wake-ups
- **Behind load balancers** → ensure heartbeat interval is **less than** the LB idle timeout (typically 60s for AWS ALB)

---

## Load Balancer Compatibility

If your app is behind a reverse proxy or load balancer, the LB may have an **idle connection timeout**. Heartbeats keep the connection alive:

```
LB idle timeout = 60s
Heartbeat interval = 10s
→ Connection stays alive (heartbeat fires before LB kills it)
```

Ensure:
```
heartbeat_interval < load_balancer_idle_timeout
```

Common defaults:
- AWS ALB: 60s
- Nginx: 60s (configurable via `proxy_read_timeout`)
- HAProxy: 50s (configurable via `timeout tunnel`)

---

## Full Configuration Example

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry messageBrokerRegistry) {
        messageBrokerRegistry.enableSimpleBroker("/queue", "/topic")
                .setHeartbeatValue(new long[]{10000, 10000})
                .setTaskScheduler(heartbeatScheduler());
        messageBrokerRegistry.setApplicationDestinationPrefixes("/app");
        messageBrokerRegistry.setUserDestinationPrefix("/user");
    }

    @Bean
    public TaskScheduler heartbeatScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    // ...other config...
}
```

---

## Key Takeaways

| Point                              | Detail                                               |
|------------------------------------|------------------------------------------------------|
| Heartbeats are transport-level     | They don't pass through `ChannelInterceptor`         |
| `TaskScheduler` is mandatory       | Without it, heartbeats are configured but never sent |
| Negotiated between client & server | Both sides exchange preferences during CONNECT       |
| Dead detection ≈ 3× interval       | With 10s heartbeats → ~30s detection                 |
| Keep below LB idle timeout         | Prevents load balancer from killing idle connections |
| No application code needed         | Fully handled by Spring's STOMP transport layer      |

