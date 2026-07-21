# Session Limits & DoS Protection

WebSocket connections are **persistent and long-lived** — unlike HTTP where a request comes and goes in milliseconds, a WebSocket stays open for hours. This makes them a much bigger attack surface.

---

## 1. Transport-Level Limits

### Configuration (`WebSocketConfig`)

```java
@Override
public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
    registry.setMessageSizeLimit(64 * 1024);       // 64KB max inbound message size
    registry.setSendBufferSizeLimit(512 * 1024);   // 512KB outbound buffer per session
    registry.setSendTimeLimit(20_000);              // 20s max time to send a message to client
}
```

### What Each Setting Prevents

| Setting                  | Default | Our Value | What it prevents                                           |
|--------------------------|---------|-----------|------------------------------------------------------------|
| `setMessageSizeLimit`    | 64KB    | 64KB      | Client sending huge messages to exhaust server memory      |
| `setSendBufferSizeLimit` | 512KB   | 512KB     | Slow clients accumulating unsent messages in server memory |
| `setSendTimeLimit`       | 10s     | 20s       | Hanging connections that never drain their buffer          |

### Behavior When Limits Are Exceeded

- **Message size exceeded** → Connection closed immediately, message rejected
- **Send buffer exceeded** → Session forcefully terminated
- **Send time exceeded** → Session forcefully terminated

These three settings are a **single method override** (5 lines of code) with zero downsides. Always have them.

---

## 2. Rate Limiting (Messages Per Second)

### Why

Without it, a single client can send **thousands of messages per second**, overwhelming your `@MessageMapping` handlers, database, or downstream services. Even an authenticated user could accidentally (or intentionally) flood your server.

### Implementation

#### `RateLimiterInterceptor.java`

```java
@Component
public class RateLimiterInterceptor implements ChannelInterceptor {
    private static final int MAX_MESSAGES_PER_SECOND = 5;
    private final Map<String, SessionRateTracker> sessionRateTrackerMap = new ConcurrentHashMap<>();

    @Override
    public @Nullable Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        var accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.SEND.equals(accessor.getCommand())) {
            var sessionId = accessor.getSessionId();
            if (sessionId == null) {
                return message;
            }
            SessionRateTracker sessionRateTracker = sessionRateTrackerMap.computeIfAbsent(
                    sessionId, k -> new SessionRateTracker(MAX_MESSAGES_PER_SECOND));
            if (!sessionRateTracker.allowMessage()) {
                throw new IllegalStateException(
                        "Rate limit exceeded: max " + MAX_MESSAGES_PER_SECOND + " messages/second");
            }
        }

        // cleanup on disconnect
        if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            var sessionId = accessor.getSessionId();
            if (sessionId != null) {
                sessionRateTrackerMap.remove(sessionId);
            }
        }
        return message;
    }
}
```

#### `SessionRateTracker.java`

Sliding window rate tracker with synchronization to prevent race conditions:

```java
public class SessionRateTracker {

    private final AtomicInteger counter = new AtomicInteger(0);
    private volatile long windowStart = System.currentTimeMillis();
    private final int maxMessagesPerSecond;
    private final Object LOCK = new Object();

    public SessionRateTracker(int maxMessagesPerSecond) {
        this.maxMessagesPerSecond = maxMessagesPerSecond;
    }

    boolean allowMessage() {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (now - windowStart > 1000) {
                counter.set(1);
                windowStart = now;
                return true;
            }
        }
        return counter.incrementAndGet() <= maxMessagesPerSecond;
    }
}
```

### Design Decisions

- **Tracks by sessionId** (not username) — a user with multiple tabs gets independent rate limits per session
- **Cleanup on DISCONNECT** — prevents memory leak from abandoned sessions
- **Synchronized window reset** — prevents race condition where two threads both reset the counter simultaneously

---

## 3. Session Limit Per User

### Why

Without it, a user (or attacker with a valid token) could open **10,000 connections**, each consuming server threads, memory, and scheduler resources. This is a simple and effective DoS vector.

### Architecture

```
SessionLimitPerUserInterceptor  →  UserSessionTracker  ←  WebSocketEventListener
```

No circular dependencies. The shared `UserSessionTracker` service is used by both.

### Implementation

#### `UserSessionTracker.java` (shared service)

```java
@Component
public class UserSessionTracker {
    private static final int MAX_SESSIONS_PER_USER = 2;
    private final Map<String, AtomicInteger> userSessionCountMap = new ConcurrentHashMap<>();

    public void registerSession(String username) {
        var count = userSessionCountMap.computeIfAbsent(username, _ -> new AtomicInteger(0));
        if (count.incrementAndGet() > MAX_SESSIONS_PER_USER) {
            count.decrementAndGet();
            throw new IllegalStateException(
                    "Max concurrent sessions (" + MAX_SESSIONS_PER_USER + ") exceeded for user: " + username);
        }
    }

    public void removeSession(String username) {
        if (username == null) {
            return;
        }
        var count = userSessionCountMap.get(username);
        if (count != null && count.decrementAndGet() <= 0) {
            userSessionCountMap.remove(username);
        }
    }
}
```

#### `SessionLimitPerUserInterceptor.java` (handles CONNECT)

```java
@Component
public class SessionLimitPerUserInterceptor implements ChannelInterceptor {

    private final UserSessionTracker userSessionTracker;

    public SessionLimitPerUserInterceptor(UserSessionTracker userSessionTracker) {
        this.userSessionTracker = userSessionTracker;
    }

    @Override
    public @Nullable Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        var accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        var principal = accessor.getUser();
        if (principal == null) return message;

        var username = principal.getName();
        if (username == null) return message;

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            userSessionTracker.registerSession(username);
        }
        return message;
    }
}
```

#### `WebSocketEventListener.java` (handles disconnect cleanup)

```java
@EventListener
void handleWebsocketDisconnectListener(SessionDisconnectEvent event) {
    var principal = event.getUser();
    if (principal == null) return;
    userSessionTracker.removeSession(principal.getName());
}
```

### Design Decisions

| Decision                          | Reason                                                                |
|-----------------------------------|-----------------------------------------------------------------------|
| Interceptor only handles CONNECT  | Single responsibility — only gates new connections                    |
| Event listener handles cleanup    | `SessionDisconnectEvent` **always** fires, even on abrupt disconnects |
| No DISCONNECT in interceptor      | Avoids double-decrement (both interceptor and event listener firing)  |
| `accessor.getUser()` for username | No dependency on `SecurityContextHolder`                              |
| Shared `UserSessionTracker`       | No circular dependency between interceptor and listener               |

### Why `removeSession` is idempotent

If `removeSession` is called when the user isn't in the map, `userSessionCountMap.get(username)` returns `null` and nothing happens. Safe to call multiple times.

---

## Interceptor Registration Order

```java
@Override
public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(
            authorizationInterceptor,           // 1st: authenticate (JWT → Principal)
            securityContextChannelInterceptor,  // 2nd: Principal → SecurityContextHolder
            sessionLimitPerUserInterceptor,     // 3rd: limit connections per user
            authorizationChannelInterceptor,    // 4th: authorize destinations
            rateLimiterInterceptor,             // 5th: rate limit messages
            websocketTracingChannelInterceptor  // 6th: trace
    );
}
```

Each interceptor does **one thing**:

| # | Interceptor                          | Responsibility                                |
|---|--------------------------------------|-----------------------------------------------|
| 1 | `AuthorizationInterceptor`           | "Are you who you say you are?"                |
| 2 | `SecurityContextChannelInterceptor`  | Bridge Principal to SecurityContext           |
| 3 | `SessionLimitPerUserInterceptor`     | "Are you allowed to open another connection?" |
| 4 | `AuthorizationChannelInterceptor`    | "Are you allowed to access this destination?" |
| 5 | `RateLimiterInterceptor`             | "Are you sending too fast?"                   |
| 6 | `WebsocketTracingChannelInterceptor` | Observe and trace                             |

---

## Testing

### Test Rate Limiting (5 msg/s)

Send more than 5 messages in one second:

```javascript
// Send 10 messages rapidly
for (var i = 0; i < 10; i++) {
    stompClient.send('/app/notification', {}, JSON.stringify({content: 'msg ' + i}));
}
```

**Expected:** First 5 messages succeed. Messages 6-10 trigger `IllegalStateException: Rate limit exceeded`. Client gets disconnected.

### Test Message Size Limit (64KB)

```javascript
var largeMessage = 'x'.repeat(65 * 1024); // 65KB
stompClient.send('/app/notification', {}, largeMessage);
```

**Expected:** Connection closes immediately. Server rejects the oversized message.

### Test Session Limit (2 per user)

Open 3 browser tabs, connect with the same JWT token:

```javascript
// Tab 1: connects ✅ (count = 1)
// Tab 2: connects ✅ (count = 2)
// Tab 3: connects ❌ (count > 2, rejected)
```

**Expected:** Tabs 1 and 2 connect. Tab 3 rejected with `Max concurrent sessions (2) exceeded`.

**Cleanup test:** Close tab 1 → open new tab → should connect ✅ (count: 2 → 1 → 2).

### Test HTML Page

```html
<!DOCTYPE html>
<html>
<head>
    <script src="https://cdn.jsdelivr.net/npm/sockjs-client/dist/sockjs.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/stompjs/lib/stomp.min.js"></script>
</head>
<body>
<button onclick="testRateLimit()">Test Rate Limit (send 10 msgs)</button>
<button onclick="testMessageSize()">Test Message Size (65KB)</button>
<button onclick="testSessionLimit()">Test Session Limit (3rd connection)</button>
<pre id="output"></pre>

<script>
    var token = 'YOUR_JWT_TOKEN';
    var stompClient;

    function connect(callback) {
        var socket = new SockJS('http://localhost:8080/ws');
        stompClient = Stomp.over(socket);
        stompClient.connect({'Authorization': 'Bearer ' + token}, function(frame) {
            log('Connected: ' + frame);
            stompClient.subscribe('/user/queue/notifications', function(msg) {
                log('Received: ' + msg.body);
            });
            if (callback) callback();
        }, function(error) {
            log('Error: ' + error);
        });
    }

    function testRateLimit() {
        connect(function() {
            log('--- Sending 10 messages rapidly ---');
            for (var i = 0; i < 10; i++) {
                stompClient.send('/app/notification', {}, JSON.stringify({content: 'msg ' + i}));
                log('Sent message ' + i);
            }
        });
    }

    function testMessageSize() {
        connect(function() {
            log('--- Sending 65KB message ---');
            var largeMsg = 'x'.repeat(65 * 1024);
            stompClient.send('/app/notification', {}, largeMsg);
            log('Large message sent (or connection closed)');
        });
    }

    function testSessionLimit() {
        log('--- Opening 3 connections ---');
        for (var i = 0; i < 3; i++) {
            (function(idx) {
                var socket = new SockJS('http://localhost:8080/ws');
                var client = Stomp.over(socket);
                client.connect({'Authorization': 'Bearer ' + token}, function() {
                    log('Connection ' + idx + ' succeeded ✅');
                }, function(error) {
                    log('Connection ' + idx + ' failed ❌: ' + error);
                });
            })(i);
        }
    }

    function log(msg) {
        document.getElementById('output').textContent += msg + '\n';
        console.log(msg);
    }

    connect();
</script>
</body>
</html>
```

---

## Recommended Values by Environment

| Setting            | Development | Production                        |
|--------------------|-------------|-----------------------------------|
| Message size limit | 64KB        | 64KB (or lower based on use case) |
| Send buffer limit  | 512KB       | 512KB                             |
| Send time limit    | 20s         | 20s                               |
| Rate limit         | 10 msg/s    | 5 msg/s                           |
| Session limit      | 5           | 2-3                               |

---

## Summary

| Protection             | What it prevents                           | Effort                                        |
|------------------------|--------------------------------------------|-----------------------------------------------|
| Message size limit     | Memory exhaustion from huge messages       | 1 line                                        |
| Send buffer limit      | Server memory exhaustion from slow clients | 1 line                                        |
| Send time limit        | Hung connections                           | 1 line                                        |
| Rate limiting          | Message flooding                           | Interceptor + tracker class                   |
| Session limit per user | Connection exhaustion                      | Interceptor + shared service + event listener |

