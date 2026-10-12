# WebSocket Tracing with Micrometer Tracing and Spring Boot

## Problem

WebSocket/STOMP messages do **not** go through the normal servlet request lifecycle once the WebSocket connection is established. This means normal HTTP tracing does not automatically cover:

- STOMP `CONNECT`, `SEND`, `SUBSCRIBE`, `UNSUBSCRIBE`, `DISCONNECT` frames
- `@MessageMapping` method execution
- Server-to-client STOMP `MESSAGE` frames
- Log correlation inside WebSocket message handling threads

The current solution adds explicit STOMP channel tracing by using a custom `ExecutorChannelInterceptor`.

---

## Current Implementation Summary

The project currently uses:

- `io.micrometer.tracing.Tracer`
- `io.micrometer.tracing.Span`
- `io.micrometer.tracing.TraceContext`
- `ExecutorChannelInterceptor`
- STOMP session attributes for trace context propagation across the lifetime of a WebSocket session
- `ThreadLocal` holders for span/scope lifecycle management

Current implementation file:

```text
src/main/java/com/moh/yehia/websocket/interceptor/WebsocketTracingChannelInterceptor.java
```

The interceptor traces these STOMP commands:

| Command       | Direction | Span name                 |
|---------------|-----------|---------------------------|
| `CONNECT`     | inbound   | `websocket.authorization` |
| `SEND`        | inbound   | `websocket.inbound`       |
| `SUBSCRIBE`   | inbound   | `websocket.inbound`       |
| `UNSUBSCRIBE` | inbound   | `websocket.inbound`       |
| `DISCONNECT`  | inbound   | `websocket.inbound`       |
| `MESSAGE`     | outbound  | `websocket.outbound`      |

Each span is tagged with:

| Tag           | Meaning                                              |
|---------------|------------------------------------------------------|
| `destination` | STOMP destination, or `unknown` when absent          |
| `direction`   | `inbound`, `outbound`, or `unknown`                  |
| `sessionId`   | WebSocket/STOMP session ID, or `unknown` when absent |

---

## Why `ExecutorChannelInterceptor`?

`ChannelInterceptor` provides methods like `preSend()` and `afterSendCompletion()`. These run when a message is sent to a channel, but not necessarily on the same thread that executes the message handler.

`ExecutorChannelInterceptor` adds:

- `beforeHandle()`
- `afterMessageHandled()`

These callbacks wrap actual message handling and are the correct place to open a tracing scope for `@MessageMapping` execution and outbound message delivery.

That is why the current interceptor implements:

```java
public class WebsocketTracingChannelInterceptor implements ExecutorChannelInterceptor
```

---

## Registration in `WebSocketConfig`

The interceptor is registered on both inbound and outbound channels.

### Inbound channel

```java
@Override
public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(
            websocketTracingChannelInterceptor,
            authorizationInterceptor,
            securityContextChannelInterceptor,
            sessionLimitPerUserInterceptor,
            authorizationChannelInterceptor,
            messagesPerSessionRateLimiterInterceptor
    );
}
```

The tracing interceptor is intentionally first. This allows it to create a `CONNECT` span before authentication/authorization interceptors run, so connection failures can still be associated with a span.

### Outbound channel

```java
@Override
public void configureClientOutboundChannel(ChannelRegistration registration) {
    registration.interceptors(websocketTracingChannelInterceptor);
}
```

This traces server-to-client STOMP `MESSAGE` frames, including messages sent through `SimpMessagingTemplate.convertAndSendToUser(...)`.

---

## Lifecycle: `CONNECT`

`CONNECT` is handled differently from normal messages.

### `preSend()`

For `CONNECT`, the interceptor:

1. Reads STOMP metadata from `StompHeaderAccessor`
2. Sets internal tracing headers while headers are still mutable
3. Creates a span named `ws.authorization`
4. Opens the span scope immediately
5. Stores the span and scope in `ThreadLocal`s
6. Stores the `TraceContext` in STOMP session attributes

Simplified flow:

```java
if (StompCommand.CONNECT.equals(command)) {
    Span span = createSpan("websocket.authorization", null, destination, direction, sessionId);
    Tracer.SpanInScope scope = tracer.withSpan(span);
    CONNECT_SPAN_HOLDER.set(span);
    CONNECT_SCOPE_HOLDER.set(scope);
    getSessionAttributes(headerAccessors).put(SESSION_TRACE_CONTEXT_KEY, span.context());
}
```

The session attribute key is:

```java
micrometer.session.trace.context
```

This makes the `CONNECT` span context available to later frames in the same WebSocket session.

### `afterSendCompletion()`

The `CONNECT` span is closed in `afterSendCompletion()`:

```java
Span span = CONNECT_SPAN_HOLDER.get();
Tracer.SpanInScope scope = CONNECT_SCOPE_HOLDER.get();

if (span != null && ex != null) {
    span.error(ex);
}
if (scope != null) {
    scope.close();
    CONNECT_SCOPE_HOLDER.remove();
}
if (span != null) {
    span.end();
    CONNECT_SPAN_HOLDER.remove();
}
```

If authentication or another inbound interceptor throws during `CONNECT`, the exception is recorded on the span.

---

## Lifecycle: `SEND`, `SUBSCRIBE`, `UNSUBSCRIBE`, `DISCONNECT`, `MESSAGE`

### `preSend()`

For non-`CONNECT` supported commands, the interceptor:

1. Reads destination, direction, and session ID
2. Stores them as internal message headers
3. Reads the session trace context from STOMP session attributes
4. Stores that trace context into a message header for later use

The message header key is:

```java
micrometer.trace.context
```

This allows `beforeHandle()` to create child spans using the `CONNECT` span context as parent.

### `beforeHandle()`

For supported non-`CONNECT` commands, the interceptor:

1. Reads the internal metadata headers
2. Reads the optional parent `TraceContext`
3. Creates a span named `websocket.inbound` or `websocket.outbound`
4. Sets the parent if a session trace context exists
5. Opens the span scope
6. Stores span/scope in `ThreadLocal`s

Simplified flow:

```java
TraceContext parentContext = null;
Object traceContext = headerAccessors.getHeader(TRACE_CONTEXT_KEY);
if (traceContext instanceof TraceContext storedTraceContext) {
    parentContext = storedTraceContext;
}

Span span = createSpan("websocket." + direction, parentContext, destination, direction, sessionId);
Tracer.SpanInScope scope = tracer.withSpan(span);
MESSAGE_SPAN_HOLDER.set(span);
MESSAGE_SCOPE_HOLDER.set(scope);
```

### `afterMessageHandled()`

After the handler completes, the interceptor:

1. Records an error on the span if an exception exists
2. Closes the span scope
3. Ends the span
4. Removes `ThreadLocal` values

```java
Span span = MESSAGE_SPAN_HOLDER.get();
Tracer.SpanInScope scope = MESSAGE_SCOPE_HOLDER.get();

if (span != null && ex != null) {
    span.error(ex);
}
if (scope != null) {
    scope.close();
    MESSAGE_SCOPE_HOLDER.remove();
}
if (span != null) {
    span.end();
    MESSAGE_SPAN_HOLDER.remove();
}
```

---

## Trace Relationship Model

The current implementation creates this relationship:

```text
CONNECT span: ws.authorization
    ├── SEND span: websocket.inbound
    ├── SUBSCRIBE span: websocket.inbound
    ├── UNSUBSCRIBE span: websocket.inbound
    ├── DISCONNECT span: websocket.inbound
    └── MESSAGE span: websocket.outbound
```

The `CONNECT` span context is stored in the WebSocket session attributes and reused as the parent for later frames.

Important nuance: the `CONNECT` span itself is ended after the `CONNECT` frame completes. Its `TraceContext` can still be used as a parent context for later spans, so all frames in the same WebSocket session can share the same trace lineage.

---

## Why Internal Headers Are Used

The interceptor stores calculated values in internal message headers during `preSend()`:

```java
websocket.destination
websocket.direction
websocket.sessionId
micrometer.trace.context
```

This is done because `preSend()` is the safest place to read and write message headers while they are still mutable.

Later, `beforeHandle()` can read those values even though headers may already be immutable.

---

## Why `isMutable()` Is Checked

Some STOMP messages, especially framework-generated messages, may have immutable headers. Calling `setHeader()` on immutable headers throws:

```text
IllegalStateException: Already immutable
```

The current implementation avoids this by returning early when headers are not mutable:

```java
if (headerAccessors == null || !headerAccessors.isMutable()) {
    return message;
}
```

This means immutable frames are skipped by this interceptor rather than causing the WebSocket pipeline to fail.

---

## Why `ThreadLocal` Is Used

`Span` and `SpanInScope` must be available later in the same processing lifecycle so they can be closed correctly.

The implementation uses separate `ThreadLocal`s for `CONNECT` and normal message handling:

```java
private static final ThreadLocal<Span> CONNECT_SPAN_HOLDER = new ThreadLocal<>();
private static final ThreadLocal<Tracer.SpanInScope> CONNECT_SCOPE_HOLDER = new ThreadLocal<>();
private static final ThreadLocal<Span> MESSAGE_SPAN_HOLDER = new ThreadLocal<>();
private static final ThreadLocal<Tracer.SpanInScope> MESSAGE_SCOPE_HOLDER = new ThreadLocal<>();
```

This avoids storing mutable span/scope objects in STOMP headers and avoids the previous `Already immutable` issue.

The implementation also removes the values after use, which is important because STOMP channels use executor threads that are reused for many messages.

---

## Current Implementation Code Shape

The current interceptor has this structure:

```java
@Configuration
public class WebsocketTracingChannelInterceptor implements ExecutorChannelInterceptor {
    private final Tracer tracer;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // Resolve STOMP command, destination, direction, sessionId
        // Store metadata headers while mutable
        // For CONNECT: create ws.authorization span and store session TraceContext
        // For other commands: copy session TraceContext into message header
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        // For supported non-CONNECT commands:
        // create websocket.inbound/outbound span
        // use stored TraceContext as parent when available
        // open span scope
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        // Close/end CONNECT span
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler, Exception ex) {
        // Close/end normal message span
    }
}
```

---

## Logging Configuration

Trace and span IDs are included in logs through the logging correlation pattern in `application.yaml`:

```yaml
logging:
  pattern:
    correlation: "[${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
```

Example log shape:

```text
[spring-boot-4-websocket,52ef1a99a57e29c6cd5fe6bf0daf6cd7,d16827a59e735d47]
```

---

## Tracing Configuration

Tracing is configured through Spring Boot Actuator/Micrometer properties:

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
    export:
      enabled: true
```

`probability: 1.0` means every trace is sampled. This is useful for learning and local testing. In production, consider reducing it depending on traffic volume.

The project also has:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-opentelemetry</artifactId>
</dependency>
```

This provides OpenTelemetry integration/export support through Spring Boot, while the interceptor itself uses Micrometer's `Tracer` abstraction directly.

---

## Current Limitations

### 1. No frontend `traceparent` extraction in the current code

An earlier design extracted W3C `traceparent` headers from STOMP native headers. The current implementation does **not** directly extract frontend `traceparent` / `tracestate` headers.

Instead, it creates a new `CONNECT` span and uses that span context as the parent for later STOMP frames in the same session.

If frontend-to-backend distributed trace continuity is required, the interceptor would need to add explicit propagation extraction from STOMP native headers.

### 2. Parent context is session-based

All later frames use the stored `CONNECT` trace context as parent when available. This groups WebSocket activity by session, but it does not create a separate parent trace per frontend action unless additional `traceparent` extraction is implemented.

### 3. Immutable messages are skipped

If a message has immutable headers by the time `preSend()` runs, this interceptor returns the message unchanged. This is safer than failing the WebSocket pipeline, but it means such frames may not get custom WebSocket spans.

---

## Optional Future Enhancement: Frontend Trace Propagation

If the frontend uses OpenTelemetry JS and sends W3C trace context headers with STOMP frames, the backend can extract them from native headers.

Frontend example:

```javascript
const span = tracer.startSpan('websocket.send');
const ctx = trace.setSpan(context.active(), span);

const headers = {};
propagation.inject(ctx, headers);

stompClient.publish({
  destination: '/app/notification',
  headers: {
    traceparent: headers.traceparent,
    tracestate: headers.tracestate || ''
  },
  body: JSON.stringify({ content: 'hello world' })
});

span.end();
```

Backend support would require adding an OpenTelemetry `TextMapGetter<StompHeaderAccessor>` or Micrometer propagation extraction, then using the extracted context as the parent for the message span.

This is not part of the current implementation.

---

## Common Errors

### `IllegalStateException: Already immutable`

Cause: calling `setHeader()` after Spring has made message headers immutable.

Current mitigation:

```java
if (headerAccessors == null || !headerAccessors.isMutable()) {
    return message;
}
```

### Missing trace/span IDs in `@MessageMapping` logs

Check that:

1. `WebsocketTracingChannelInterceptor` is registered in `configureClientInboundChannel()`
2. The method is executed for a supported STOMP command
3. The span scope is opened in `beforeHandle()`
4. The logging pattern includes `%X{traceId}` and `%X{spanId}`
5. Tracing dependencies are on the classpath

### Missing trace/span IDs on outbound messages

Check that:

1. `WebsocketTracingChannelInterceptor` is registered in `configureClientOutboundChannel()`
2. The outbound frame command is `MESSAGE`
3. The message has a session ID and/or session attributes available

---

## How It Works End-to-End

```text
1. Client sends STOMP CONNECT

2. preSend(CONNECT)
   -> create ws.authorization span
   -> open span scope
   -> store TraceContext in session attributes

3. Authorization/session interceptors run
   -> auth failures are attached to the CONNECT span when propagated as channel exceptions

4. afterSendCompletion(CONNECT)
   -> record error if present
   -> close CONNECT scope
   -> end CONNECT span

5. Client sends SEND /app/notification

6. preSend(SEND)
   -> copy session TraceContext into message headers
   -> store destination/direction/sessionId headers

7. beforeHandle(SEND)
   -> create websocket.inbound span
   -> set parent to stored session TraceContext
   -> open span scope

8. @MessageMapping executes
   -> logs include traceId/spanId

9. afterMessageHandled(SEND)
   -> record handler error if present
   -> close scope
   -> end span

10. Server sends MESSAGE to client

11. outbound preSend/beforeHandle/afterMessageHandled
   -> create websocket.outbound span
   -> close/end after delivery handling
```

---

## Key Concepts

| Concept                      | Description                                                                             |
|------------------------------|-----------------------------------------------------------------------------------------|
| `Tracer`                     | Micrometer abstraction used to create spans                                             |
| `Span`                       | A traced unit of work, such as `CONNECT` or `SEND` handling                             |
| `TraceContext`               | Trace identity stored in session attributes and reused as parent context                |
| `SpanInScope`                | Scope that makes the span current on the executing thread                               |
| `ExecutorChannelInterceptor` | STOMP channel interceptor with handler-thread callbacks                                 |
| `ThreadLocal`                | Used to hold span/scope until the matching completion callback                          |
| `StompHeaderAccessor`        | Gives access to STOMP command, destination, session ID, session attributes, and headers |
| `isMutable()`                | Prevents writing headers after Spring marks them immutable                              |
| MDC                          | Logging context where traceId/spanId are made available by tracing instrumentation      |
| `CONNECT` span               | Session-root span currently used as parent context for later STOMP frame spans          |

---

## Production Notes

- Keep `sessionId` as trace metadata only. Do not use it as a low-cardinality metric tag in custom metrics because it has very high cardinality.
- Consider lowering sampling below `1.0` in production for high-traffic systems.
- If you need browser-to-backend trace continuity, add explicit `traceparent` extraction from STOMP native headers.
- Keep `ThreadLocal` cleanup mandatory. Executor threads are reused and leaked trace context can corrupt logs/traces for unrelated messages.
- Register tracing before auth/rate-limit interceptors if you want rejected frames to be traced.
