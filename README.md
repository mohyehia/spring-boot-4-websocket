# spring-boot-4-websocket

## Overview

A Spring Boot 4 WebSocket project using STOMP protocol for real-time, user-specific notifications.

---

## WebSocket Configuration

### `WebSocketConfig.java`

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry stompEndpointRegistry) {
        stompEndpointRegistry.addEndpoint("/ws").setAllowedOrigins("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry messageBrokerRegistry) {
        messageBrokerRegistry.enableSimpleBroker("/queue");
        messageBrokerRegistry.setApplicationDestinationPrefixes("/app");
        messageBrokerRegistry.setUserDestinationPrefix("/user");
    }
}
```

### Configuration Breakdown

| Method                                      | Purpose                                                                                                                                                     |
|---------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `enableSimpleBroker("/queue")`              | Enables an in-memory message broker. Messages with destinations starting with `/queue` are routed directly to subscribed clients.                           |
| `setApplicationDestinationPrefixes("/app")` | Messages sent from the client with destinations starting with `/app` are routed to server-side `@MessageMapping` methods for processing.                    |
| `setUserDestinationPrefix("/user")`         | Enables user-specific messaging. When a client subscribes to `/user/queue/notifications`, Spring rewrites it internally to a unique session-specific queue. |

---

## Endpoints

| Endpoint                    | Type                        | Description                                                               |
|-----------------------------|-----------------------------|---------------------------------------------------------------------------|
| `/ws`                       | WebSocket Connection        | The STOMP endpoint clients connect to (with SockJS fallback).             |
| `/app/notification`         | Client → Server             | Clients send messages here; routed to `@MessageMapping("/notification")`. |
| `/user/queue/notifications` | Server → Client (Subscribe) | Clients subscribe here to receive their private notifications.            |

---

## Message Flow

```
Client connects to:       ws://localhost:8080/ws (SockJS)
Client subscribes to:     /user/queue/notifications
Client sends message to:  /app/notification

Server receives at:       @MessageMapping("/notification")
Server sends privately:   convertAndSendToUser(sessionId, "/queue/notifications", payload, headers)
```

---

## SimpMessageHeaderAccessor

When a client sends a message, Spring injects `SimpMessageHeaderAccessor` into your `@MessageMapping` method. It provides metadata about the incoming message:

| Field                   | Description                                                                                         |
|-------------------------|-----------------------------------------------------------------------------------------------------|
| `simpSessionId`         | Unique ID per WebSocket connection — **used as user identifier** when no authentication is present. |
| `simpDestination`       | The destination the message was sent to.                                                            |
| `nativeHeaders`         | STOMP headers sent by the client.                                                                   |
| `simpSessionAttributes` | A map where you can store custom data per session.                                                  |

### Usage — Sending Private Notifications

```java
@MessageMapping("/notification")
void sendNotification(@Payload String message, SimpMessageHeaderAccessor simpMessageHeaderAccessor) {
    String sessionId = simpMessageHeaderAccessor.getSessionId();

    // Create headers targeting the specific session
    SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();
    headerAccessor.setSessionId(sessionId);
    headerAccessor.setLeaveMutable(true);

    // Send a private notification to THIS specific client
    simpMessagingTemplate.convertAndSendToUser(
        sessionId,
        "/queue/notifications",
        "Hello, this is your private message!",
        headerAccessor.getMessageHeaders()
    );
}
```

---

## CORS Configuration

The `setAllowedOrigins()` value must match the **full origin** (protocol + host + port) of the client:

| Client Origin                       | Configuration                                 |
|-------------------------------------|-----------------------------------------------|
| File system (`null`)                | `setAllowedOrigins("*")`                      |
| `http://localhost:5500`             | `setAllowedOrigins("http://localhost:5500")`  |
| `http://localhost:63342` (IntelliJ) | `setAllowedOrigins("http://localhost:63342")` |
| Any origin (development)            | `setAllowedOrigins("*")`                      |

> **Note:** Just using `"localhost"` without protocol and port will NOT work.

---

## Serving the HTML Test Page

Options to serve an HTML page from a local server:

- **Python:** `python -m http.server 5500` → `http://localhost:5500`
- **Node.js:** `npx serve -p 5500` → `http://localhost:5500`
- **IntelliJ Built-in Server:** Open HTML file → click browser icon → served from `http://localhost:63342`

---

## Key Concept: User-Specific Messaging Without Authentication

Since there's no Spring Security / `Principal`, users are identified by their **session ID**. Each connected client gets a unique `simpSessionId`. The flow is:

1. Client connects → gets assigned a session ID (e.g., `nrajwgfl`).
2. Client subscribes to `/user/queue/notifications` → Spring internally maps to a session-specific destination.
3. Server uses `convertAndSendToUser(sessionId, "/queue/notifications", payload, headers)` → only that client receives it.

If Spring Security is configured, you can use the authenticated username instead of session ID:
```java
simpMessagingTemplate.convertAndSendToUser("john", "/queue/notifications", payload);
```
