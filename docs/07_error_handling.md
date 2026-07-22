# WebSocket Error Handling

## Overview

WebSocket error handling requires **two layers** because errors can occur at different stages of message processing:

```
Client message → Interceptor chain → @MessageMapping handler
                      │                        │
                      ▼                        ▼
          StompSubProtocolErrorHandler   @MessageExceptionHandler
          (transport/interceptor errors) (business logic errors)
                      │                        │
                      ▼                        ▼
            STOMP ERROR frame            /user/queue/errors
            (client disconnects)         (client stays connected)
```

---

## Layer 1: `StompSubProtocolErrorHandler` — Transport & Interceptor Errors

### What it catches

| Error Source              | Example                                 |
|---------------------------|-----------------------------------------|
| Interceptor exceptions    | Auth failure, rate limit, session limit |
| Malformed STOMP frames    | Invalid headers, corrupt messages       |
| Message delivery failures | Unknown destination, broker errors      |

### Why needed

Exceptions thrown in interceptors **never reach** `@MessageExceptionHandler`. Without this handler, the client gets disconnected with a raw protocol error (`CloseStatus[code=1002]`) and no useful information.

### Implementation

```java
package com.moh.yehia.websocket.exception;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

import java.nio.charset.StandardCharsets;

@Component
public class CustomStompErrorHandler extends StompSubProtocolErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(CustomStompErrorHandler.class);

    @Override
    public @Nullable Message<byte[]> handleClientMessageProcessingError(
            @Nullable Message<byte[]> clientMessage, @NonNull Throwable ex) {
        log.error("STOMP client message processing error: {}", ex.getMessage(), ex);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(getErrorMessage(ex));
        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(
                getErrorBody(ex).getBytes(StandardCharsets.UTF_8),
                accessor.getMessageHeaders()
        );
    }

    private String getErrorMessage(Throwable ex) {
        if (ex.getCause() != null) {
            Throwable cause = ex.getCause();
            if (cause instanceof IllegalArgumentException) {
                return "Authentication failed";
            }
            if (cause instanceof IllegalStateException) {
                return cause.getMessage(); // Rate limit, session limit
            }
            if (cause instanceof MessageDeliveryException) {
                return cause.getMessage();
            }
        }
        return "Server error";
    }

    private String getErrorBody(Throwable ex) {
        if (ex.getCause() != null) {
            Throwable cause = ex.getCause();
            if (cause instanceof IllegalArgumentException) {
                return "Authentication failed: " + cause.getMessage();
            }
            if (cause instanceof IllegalStateException) {
                return cause.getMessage();
            }
        }
        return "An unexpected error occurred";
    }
}
```

### Registration

Register in `WebSocketConfig` via `registerStompEndpoints`:

```java
@Override
public void registerStompEndpoints(StompEndpointRegistry stompEndpointRegistry) {
    stompEndpointRegistry.addEndpoint("/ws").setAllowedOrigins("*").withSockJS();
    stompEndpointRegistry.setErrorHandler(customStompErrorHandler);
}
```

### What the client receives

A STOMP ERROR frame before the connection closes:

```
ERROR
message:Rate limit exceeded: max 5 messages/second
content-type:text/plain

Rate limit exceeded: max 5 messages/second
```

---

## Layer 2: `@MessageExceptionHandler` — Business Logic Errors

### What it catches

| Error Source                            | Example                                     |
|-----------------------------------------|---------------------------------------------|
| Exceptions in `@MessageMapping` methods | Validation errors, business rule violations |
| `AccessDeniedException`                 | Unauthorized access to a resource           |
| `MethodArgumentNotValidException`       | `@Valid` annotation failures                |
| Any uncaught exception in handlers      | NPE, database errors, etc.                  |

### Why needed

These errors are **recoverable** — the client should stay connected and receive an error message they can act on.

### Error DTO

```java
package com.moh.yehia.websocket.exception;

public record ErrorDTO(String code, String message, long timestamp) {
    static ErrorDTO of(String code, String message) {
        return new ErrorDTO(code, message, System.currentTimeMillis());
    }
}
```

### Implementation

```java
package com.moh.yehia.websocket.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.util.List;
import java.util.Objects;

@ControllerAdvice
public class WebsocketExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(WebsocketExceptionHandler.class);

    @MessageExceptionHandler(IllegalStateException.class)
    @SendToUser("/queue/errors")
    public ErrorDTO handleException(IllegalStateException e) {
        log.error("Error handling message => {}", e.getMessage(), e);
        return new ErrorDTO("BAD_REQUEST", e.getMessage(), System.currentTimeMillis());
    }

    @MessageExceptionHandler(AccessDeniedException.class)
    @SendToUser("/queue/errors")
    public ErrorDTO handleAccessDeniedException(AccessDeniedException e) {
        log.error("Error access denied => {}", e.getMessage(), e);
        return new ErrorDTO("FORBIDDEN", e.getMessage(), System.currentTimeMillis());
    }

    @MessageExceptionHandler(MethodArgumentNotValidException.class)
    @SendToUser("/queue/errors")
    public ErrorDTO handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        log.error("Error validation => {}", e.getMessage(), e);
        List<FieldError> fieldErrors = Objects.requireNonNull(e.getBindingResult()).getFieldErrors();
        FieldError fieldError = fieldErrors.getFirst();
        return new ErrorDTO("BAD_REQUEST", fieldError.getDefaultMessage(), System.currentTimeMillis());
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser("/queue/errors")
    public ErrorDTO handleGenericException(Exception e) {
        log.error("General application error => {}", e.getMessage(), e);
        // Don't leak internal details to the client
        return new ErrorDTO("INTERNAL_SERVER_ERROR", "An unexpected error occurred", System.currentTimeMillis());
    }
}
```

### What the client receives

A JSON message on `/user/queue/errors`:

```json
{
  "code": "BAD_REQUEST",
  "message": "Message length should be greater than 5 chars",
  "timestamp": 1752940800000
}
```

---

## Frontend Error Handling

### Subscribe to error queue + handle disconnect

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
            // Connected successfully
            reconnectAttempts = 0;

            // Subscribe to notifications
            stompClient.subscribe('/user/queue/notifications', function(msg) {
                console.log('Notification:', JSON.parse(msg.body));
            });

            // Subscribe to errors (business logic errors — stays connected)
            stompClient.subscribe('/user/queue/errors', function(msg) {
                var error = JSON.parse(msg.body);
                console.error('[' + error.code + '] ' + error.message);
                showNotification(error.message);
            });
        },
        function(errorFrame) {
            // Transport/interceptor error — connection closed
            console.error('Disconnected:', errorFrame);
            handleDisconnect(errorFrame);
        }
    );
}

function handleDisconnect(reason) {
    if (reconnectAttempts >= maxReconnectAttempts) {
        showNotification('Unable to reconnect. Please refresh the page.');
        return;
    }
    reconnectAttempts++;
    var delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 30000);
    showNotification('Reconnecting in ' + (delay / 1000) + 's...');
    setTimeout(connect, delay);
}

function showNotification(message) {
    document.getElementById('status').textContent = message;
}

connect();
```

---

## Error Flow Summary

### Interceptor errors → STOMP ERROR frame → client disconnects

```
Client sends STOMP SEND
  → AuthorizationInterceptor: invalid JWT → throws IllegalArgumentException
  → CustomStompErrorHandler.handleClientMessageProcessingError()
  → Builds STOMP ERROR frame with message "Authentication failed"
  → Client receives ERROR frame in disconnect callback
  → Connection closes
```

### Business logic errors → /user/queue/errors → client stays connected

```
Client sends STOMP SEND to /app/notification
  → @MessageMapping handler throws IllegalStateException
  → WebsocketExceptionHandler.handleException()
  → Sends ErrorDTO to /user/queue/errors
  → Client receives error in /user/queue/errors subscription
  → Connection remains open
```

---

## Exception-to-Handler Mapping

| Exception                          | Handler                     | Client experience                               |
|------------------------------------|-----------------------------|-------------------------------------------------|
| Invalid JWT                        | `CustomStompErrorHandler`   | STOMP ERROR frame → disconnected                |
| Rate limit exceeded                | `CustomStompErrorHandler`   | STOMP ERROR frame → disconnected                |
| Session limit exceeded             | `CustomStompErrorHandler`   | STOMP ERROR frame → disconnected                |
| `IllegalStateException` in handler | `WebsocketExceptionHandler` | Error on `/user/queue/errors` → stays connected |
| `AccessDeniedException` in handler | `WebsocketExceptionHandler` | Error on `/user/queue/errors` → stays connected |
| Validation error (`@Valid`)        | `WebsocketExceptionHandler` | Error on `/user/queue/errors` → stays connected |
| Any uncaught exception in handler  | `WebsocketExceptionHandler` | Error on `/user/queue/errors` → stays connected |
| Malformed STOMP frame              | `CustomStompErrorHandler`   | STOMP ERROR frame → disconnected                |
| Message too large (>64KB)          | Transport layer             | Raw disconnect (no error frame)                 |
| Send buffer exceeded (>512KB)      | Transport layer             | Raw disconnect (no error frame)                 |

---

## Security Best Practice

**Never leak internal details** in the generic exception handler:

```java
// ❌ Bad — exposes internals
return new ErrorDTO("INTERNAL_SERVER_ERROR", e.getMessage(), System.currentTimeMillis());

// ✅ Good — safe generic message
return new ErrorDTO("INTERNAL_SERVER_ERROR", "An unexpected error occurred", System.currentTimeMillis());
```

Only expose `e.getMessage()` for expected, user-facing exceptions (validation, business rules).

---

## Key Takeaways

1. **Two handlers** are needed for complete coverage: `StompSubProtocolErrorHandler` for transport/interceptor errors, `@MessageExceptionHandler` for business logic errors
2. **Interceptor errors** always disconnect the client — this is intentional (auth failure, abuse protection)
3. **Business errors** keep the client connected — send structured errors to `/user/queue/errors`
4. **Frontend** must handle both: subscribe to `/user/queue/errors` for recoverable errors, and handle the disconnect callback for connection-level errors with reconnection logic
5. **Don't leak internal details** in generic error handlers

