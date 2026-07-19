# WebSocket Tracing with Micrometer, OpenTelemetry & Spring Boot

## Problem

WebSocket/STOMP messages **do not** go through the standard servlet filter chain. This means:
- No automatic trace context (traceId/spanId) is generated for `@MessageMapping` methods
- Log correlation is missing for WebSocket message handling
- Outbound messages (server → client) are also untraced

## Solution: Custom `ExecutorChannelInterceptor`

Implement an `ExecutorChannelInterceptor` that:
1. Creates an `Observation` for each STOMP frame (inbound and outbound)
2. Opens a scope so the traceId/spanId propagate to the MDC
3. Extracts parent trace context from frontend (`traceparent` header) for distributed tracing
4. Tags spans with direction and session ID

---

## Why `ExecutorChannelInterceptor` instead of `ChannelInterceptor`?

- `ChannelInterceptor` only provides `preSend` / `afterSendCompletion` — these run when the message is dispatched to the channel, **not necessarily on the same thread** as your `@MessageMapping` handler.
- `ExecutorChannelInterceptor` adds `beforeHandle` / `afterMessageHandled` — these run on the **same thread** as the message handler, which is required for MDC propagation.

---

## Key Design Decisions

### 1. Guard with `isMutable()` before setting headers

Some STOMP frames (e.g., system-generated `DISCONNECT`) have **immutable headers**. Calling `setHeader()` on them throws `IllegalStateException: Already immutable`. Always check `headerAccessors.isMutable()` before writing.

### 2. Trace all STOMP commands, not just `SEND`

| Command       | Direction  | Description                        |
|---------------|------------|------------------------------------|
| `SEND`        | inbound    | Client sends a message to server   |
| `CONNECT`     | inbound    | Client opens WebSocket connection  |
| `SUBSCRIBE`   | inbound    | Client subscribes to a destination |
| `UNSUBSCRIBE` | inbound    | Client unsubscribes                |
| `DISCONNECT`  | inbound    | Client disconnects                 |
| `MESSAGE`     | outbound   | Server sends a message to client   |

### 3. Use `ThreadLocal` for the Observation Scope (headers are immutable by `beforeHandle`)

By the time `beforeHandle` is called, message headers are **immutable**. Use a `ThreadLocal` to hold the scope on the handler thread.

### 4. Extract parent trace context from frontend (Distributed Tracing)

If the frontend uses OpenTelemetry JS and sends a `traceparent` STOMP native header, the interceptor extracts it and creates a **child span** instead of a new root trace. This links frontend and backend traces together in your tracing backend (Jaeger, Zipkin, Tempo, etc.).

If no `traceparent` header is present, `propagator.extract()` returns `Context.current()` unchanged — a new root trace is created automatically.

### 5. OTel scope management

`extractedContext.makeCurrent()` returns an `io.opentelemetry.context.Scope` that **must be closed** to prevent context leaks. Store it in a `ThreadLocal` and close it in `afterMessageHandled`.

### 6. Scope close order (LIFO)

Scopes must be closed in **reverse order** of how they were opened:
1. **OTel scope** opened in `preSend` (first)
2. **Observation scope** opened in `beforeHandle` (second)

Close order in `afterMessageHandled`:
1. Close **Observation scope** first (opened last)
2. Close **OTel scope** last (opened first)

### 7. Session ID as a high-cardinality tag

Session IDs are unique per connection — thousands of unique values. They are tagged as `highCardinalityKeyValue` so they appear in **traces** (for debugging) but not in **metrics** (which would cause metric cardinality explosion).

---

## Full Implementation

```java
package com.moh.yehia.websocket.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

@Configuration
public class WebsocketTracingChannelInterceptor implements ExecutorChannelInterceptor {
    private final ObservationRegistry observationRegistry;
    private final OpenTelemetry openTelemetry;

    private static final String OBSERVATION_KEY = "micrometer.observation";
    private static final ThreadLocal<Observation.Scope> SCOPE_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Scope> OTEL_SCOPE_HOLDER = new ThreadLocal<>();
    private static final String UNKNOWN = "unknown";

    public WebsocketTracingChannelInterceptor(ObservationRegistry observationRegistry, OpenTelemetry openTelemetry) {
        this.observationRegistry = observationRegistry;
        this.openTelemetry = openTelemetry;
    }

    @Override
    public @Nullable Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        var headerAccessors = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headerAccessors != null && headerAccessors.isMutable() && (
                StompCommand.SEND.equals(headerAccessors.getCommand())
                        || StompCommand.MESSAGE.equals(headerAccessors.getCommand())
                        || StompCommand.SUBSCRIBE.equals(headerAccessors.getCommand())
                        || StompCommand.UNSUBSCRIBE.equals(headerAccessors.getCommand())
                        || StompCommand.CONNECT.equals(headerAccessors.getCommand())
                        || StompCommand.DISCONNECT.equals(headerAccessors.getCommand())
        )) {
            var destination = headerAccessors.getDestination();
            var direction = switch (headerAccessors.getCommand()) {
                case SEND, CONNECT, SUBSCRIBE, UNSUBSCRIBE, DISCONNECT -> "inbound";
                case MESSAGE -> "outbound";
                default -> UNKNOWN;
            };
            var sessionId = headerAccessors.getSessionId() == null ? UNKNOWN : headerAccessors.getSessionId();

            // Extract parent trace context from STOMP native headers (e.g., traceparent)
            if (StompCommand.SEND.equals(headerAccessors.getCommand())) {
                TextMapPropagator propagator = openTelemetry.getPropagators().getTextMapPropagator();
                Context extractedContext = propagator.extract(Context.current(), headerAccessors, STOMP_HEADER_ACCESSOR_TEXT_MAP_GETTER);
                Scope otelScope = extractedContext.makeCurrent();
                OTEL_SCOPE_HOLDER.set(otelScope);
            }

            var observation = Observation
                    .createNotStarted("websocket." + direction, observationRegistry)
                    .contextualName("websocket " + direction + " " + destination)
                    .lowCardinalityKeyValue("destination", destination != null ? destination : UNKNOWN)
                    .lowCardinalityKeyValue("direction", direction)
                    .highCardinalityKeyValue("sessionId", sessionId)
                    .start();
            headerAccessors.setHeader(OBSERVATION_KEY, observation);
        }
        return message;
    }

    @Override
    public @Nullable Message<?> beforeHandle(@NonNull Message<?> message, @NonNull MessageChannel channel, @NonNull MessageHandler handler) {
        var headerAccessors = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headerAccessors != null) {
            var observation = (Observation) headerAccessors.getHeader(OBSERVATION_KEY);
            if (observation != null) {
                Observation.Scope scope = observation.openScope();
                SCOPE_HOLDER.set(scope);
            }
        }
        return message;
    }

    @Override
    public void afterMessageHandled(@NonNull Message<?> message, @NonNull MessageChannel channel, @NonNull MessageHandler handler, @Nullable Exception ex) {
        // Close Observation scope first (opened last in beforeHandle)
        Observation.Scope scope = SCOPE_HOLDER.get();
        if (scope != null) {
            scope.close();
            SCOPE_HOLDER.remove();
            Observation observation = scope.getCurrentObservation();
            if (ex != null) {
                observation.error(ex);
            }
            observation.stop();
        }
        // Close OTel scope last (opened first in preSend)
        Scope otelScope = OTEL_SCOPE_HOLDER.get();
        if (otelScope != null) {
            otelScope.close();
            OTEL_SCOPE_HOLDER.remove();
        }
    }

    // Extract traceparent from FE if they are using OpenTelemetry JS
    private static final TextMapGetter<StompHeaderAccessor> STOMP_HEADER_ACCESSOR_TEXT_MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(StompHeaderAccessor carrier) {
            return carrier.toNativeHeaderMap().keySet();
        }

        @Override
        public String get(StompHeaderAccessor carrier, String key) {
            assert carrier != null;
            var values = carrier.getNativeHeader(key);
            return (values != null && !values.isEmpty()) ? values.getFirst() : null;
        }
    };
}
```

---

## Registration in WebSocketConfig

Register the interceptor on **both** inbound and outbound channels:

```java
@Override
public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(websocketTracingChannelInterceptor);
}

@Override
public void configureClientOutboundChannel(ChannelRegistration registration) {
    registration.interceptors(websocketTracingChannelInterceptor);
}
```

- **Inbound channel:** traces client → server messages (`SEND`, `CONNECT`, `SUBSCRIBE`, `UNSUBSCRIBE`, `DISCONNECT`)
- **Outbound channel:** traces server → client messages (`MESSAGE` from `convertAndSendToUser()`)

---

## Frontend Trace Propagation (OpenTelemetry JS)

If the frontend uses OpenTelemetry JS, it should send the `traceparent` header when sending STOMP messages:

```javascript
// Using OpenTelemetry JS
const span = tracer.startSpan('websocket.send');
const ctx = trace.setSpan(context.active(), span);

const headers = {};
propagation.inject(ctx, headers);

// Send STOMP message with trace headers
stompClient.send("/app/notification", {
    'traceparent': headers.traceparent,
    'tracestate': headers.tracestate || ''
}, JSON.stringify(payload));

span.end();
```

This allows the backend interceptor to create a **child span** linked to the frontend trace.

---

## Logging Configuration (`application.yaml`)

To see traceId/spanId in logs:

```yaml
logging:
  pattern:
    correlation: "[${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
```

---

## Common Errors

### `IllegalStateException: Already immutable`

**Cause:** Trying to call `setHeader()` on message headers after they've been made immutable (e.g., system-generated `DISCONNECT` frames).

**Solution:** Add `headerAccessors.isMutable()` check before any `setHeader()` call. Messages with immutable headers will be skipped (no tracing for those frames).

### OTel Context Scope Leak

**Cause:** `extractedContext.makeCurrent()` returns a `Scope` that must be explicitly closed. If not closed, trace context leaks to other messages processed on the same thread.

**Solution:** Store the OTel `Scope` in a `ThreadLocal` and close it in `afterMessageHandled()`.

---

## How It Works — Lifecycle

```
1. Client sends STOMP SEND to /app/notification (with optional traceparent header)

2. preSend() [headers still mutable]
   → Check isMutable() to avoid immutable header errors
   → Extract traceparent from native headers (if present) → open OTel scope
   → Create Observation with direction, destination, sessionId tags → start it
   → Store Observation in message header

3. Message dispatched to handler thread

4. beforeHandle() [same thread as handler]
   → Read Observation from header
   → Open Observation Scope (puts traceId/spanId into MDC)
   → Store scope in ThreadLocal

5. @MessageMapping method executes
   → Logs now contain traceId/spanId
   → Any downstream calls (HTTP, Kafka, DB) inherit the trace context

6. afterMessageHandled() [same thread as handler]
   → Close Observation Scope (opened last → closed first)
   → Stop Observation
   → Close OTel Scope (opened first → closed last)
   → Remove all ThreadLocals to prevent leaks
```

---

## Key Concepts

| Concept                          | Description                                                                                           |
|----------------------------------|-------------------------------------------------------------------------------------------------------|
| `Observation`                    | Micrometer's abstraction for a unit of work — generates traces and metrics                            |
| `Observation.Scope`              | When opened, puts trace context (traceId/spanId) into the current thread's MDC                        |
| `io.opentelemetry.context.Scope` | OTel scope from `makeCurrent()` — must be closed to prevent context leaks                             |
| `ExecutorChannelInterceptor`     | Extends `ChannelInterceptor` with `beforeHandle`/`afterMessageHandled` that run on the handler thread |
| `ThreadLocal`                    | Thread-scoped storage — safe because `beforeHandle` and `afterMessageHandled` run on the same thread  |
| `isMutable()`                    | Check before `setHeader()` — some frames (e.g., system DISCONNECT) have immutable headers             |
| `TextMapGetter`                  | OTel interface to extract propagation headers from a carrier (here: STOMP native headers)             |
| `traceparent`                    | W3C Trace Context header — carries traceId and spanId for distributed tracing                         |
| MDC (Mapped Diagnostic Context)  | SLF4J mechanism for attaching context (like traceId) to log statements                                |
| Low-cardinality key values       | Tags with bounded values (e.g., direction, destination) — safe for metrics                            |
| High-cardinality key values      | Tags with unbounded values (e.g., sessionId) — appear in traces only, not metrics                     |
| LIFO scope closing               | Scopes must be closed in reverse order of opening to maintain correct context nesting                 |
