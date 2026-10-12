package com.moh.yehia.websocket.interceptor;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
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

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class WebsocketTracingChannelInterceptor implements ExecutorChannelInterceptor {
    private final Tracer tracer;

    private static final ThreadLocal<Tracer.SpanInScope> CONNECT_SCOPE_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Tracer.SpanInScope> MESSAGE_SCOPE_HOLDER = new ThreadLocal<>();

    private static final String UNKNOWN = "unknown";

    private static final String SPAN_KEY = "micrometer.websocket.span";
    private static final String TRACE_CONTEXT_KEY = "micrometer.trace.context";
    private static final String DESTINATION_KEY = "websocket.destination";
    private static final String DIRECTION_KEY = "websocket.direction";
    private static final String SESSION_ID_KEY = "websocket.sessionId";
    private static final String SESSION_TRACE_CONTEXT_KEY = "micrometer.session.trace.context";

    private static final String TRACEPARENT_HEADER = "traceparent";
    private static final String W3C_TRACE_VERSION = "00";
    private static final String SAMPLED_FLAG = "01";
    private static final String NOT_SAMPLED_FLAG = "00";

    public WebsocketTracingChannelInterceptor(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    @SuppressWarnings("java:S2638")
    public @Nullable Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        var headerAccessors = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headerAccessors == null || !headerAccessors.isMutable()) {
            return message;
        }

        var command = headerAccessors.getCommand();
        if (notSupportedCommand(command)) {
            return message;
        }

        var destination = headerAccessors.getDestination();
        var direction = getDirection(command);
        String sessionId = Objects.requireNonNullElse(headerAccessors.getSessionId(), UNKNOWN);

        headerAccessors.setHeader(DESTINATION_KEY, destination);
        headerAccessors.setHeader(DIRECTION_KEY, direction);
        headerAccessors.setHeader(SESSION_ID_KEY, sessionId);

        TraceContext parentContext = resolveParentContext(headerAccessors);

        String spanName = StompCommand.CONNECT.equals(command)
                ? "websocket.authorization"
                : "websocket." + direction;

        Span span = createSpan(spanName, parentContext, destination, direction, sessionId);

        headerAccessors.setHeader(SPAN_KEY, span);

        if (StompCommand.CONNECT.equals(command)) {
            Tracer.SpanInScope scope = tracer.withSpan(span);
            CONNECT_SCOPE_HOLDER.set(scope);
            getSessionAttributes(headerAccessors).put(SESSION_TRACE_CONTEXT_KEY, span.context());
        } else {
            headerAccessors.setHeader(TRACE_CONTEXT_KEY, span.context());
        }

        if (StompCommand.MESSAGE.equals(command)) {
            injectTraceParent(headerAccessors, span.context());
        }

        return message;
    }

    @Override
    @SuppressWarnings("java:S2638")
    public @Nullable Message<?> beforeHandle(
            @NonNull Message<?> message,
            @NonNull MessageChannel channel,
            @NonNull MessageHandler handler
    ) {
        var headerAccessors = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (headerAccessors == null
                || notSupportedCommand(headerAccessors.getCommand())
                || StompCommand.CONNECT.equals(headerAccessors.getCommand())) {
            return message;
        }

        Object spanHeader = headerAccessors.getHeader(SPAN_KEY);
        if (spanHeader instanceof Span span) {
            Tracer.SpanInScope scope = tracer.withSpan(span);
            MESSAGE_SCOPE_HOLDER.set(scope);
        }

        return message;
    }

    @Override
    public void afterSendCompletion(
            @NonNull Message<?> message,
            @NonNull MessageChannel channel,
            boolean sent,
            @Nullable Exception ex
    ) {
        var headerAccessors = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headerAccessors == null || !StompCommand.CONNECT.equals(headerAccessors.getCommand())) {
            return;
        }

        Object spanHeader = headerAccessors.getHeader(SPAN_KEY);

        if (spanHeader instanceof Span span) {
            if (ex != null) {
                span.error(ex);
            }

            Tracer.SpanInScope scope = CONNECT_SCOPE_HOLDER.get();
            if (scope != null) {
                scope.close();
                CONNECT_SCOPE_HOLDER.remove();
            }

            span.end();
        } else {
            CONNECT_SCOPE_HOLDER.remove();
        }
    }

    @Override
    public void afterMessageHandled(
            @NonNull Message<?> message,
            @NonNull MessageChannel channel,
            @NonNull MessageHandler handler,
            @Nullable Exception ex
    ) {
        var headerAccessors = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headerAccessors == null || StompCommand.CONNECT.equals(headerAccessors.getCommand())) {
            return;
        }

        Object spanHeader = headerAccessors.getHeader(SPAN_KEY);

        Tracer.SpanInScope scope = MESSAGE_SCOPE_HOLDER.get();
        if (scope != null) {
            scope.close();
            MESSAGE_SCOPE_HOLDER.remove();
        }

        if (spanHeader instanceof Span span) {
            if (ex != null) {
                span.error(ex);
            }
            span.end();
        }
    }

    private static boolean notSupportedCommand(StompCommand command) {
        return !StompCommand.SEND.equals(command)
                && !StompCommand.MESSAGE.equals(command)
                && !StompCommand.SUBSCRIBE.equals(command)
                && !StompCommand.UNSUBSCRIBE.equals(command)
                && !StompCommand.CONNECT.equals(command)
                && !StompCommand.DISCONNECT.equals(command);
    }

    private static String getDirection(StompCommand command) {
        return switch (Objects.requireNonNull(command)) {
            case SEND, CONNECT, SUBSCRIBE, UNSUBSCRIBE, DISCONNECT -> "inbound";
            case MESSAGE -> "outbound";
            default -> UNKNOWN;
        };
    }

    private static TraceContext resolveParentContext(StompHeaderAccessor headerAccessors) {
        var sessionAttributes = headerAccessors.getSessionAttributes();
        if (sessionAttributes == null) {
            return null;
        }

        Object sessionContext = sessionAttributes.get(SESSION_TRACE_CONTEXT_KEY);
        if (sessionContext instanceof TraceContext traceContext) {
            return traceContext;
        }

        return null;
    }

    private Span createSpan(
            String spanName,
            @Nullable TraceContext parentContext,
            @Nullable String destination,
            String direction,
            String sessionId
    ) {
        Span.Builder builder = tracer.spanBuilder()
                .name(spanName)
                .tag("destination", destination != null ? destination : UNKNOWN)
                .tag("direction", direction)
                .tag("sessionId", sessionId);

        if (parentContext != null) {
            builder.setParent(parentContext);
        }

        return builder.start();
    }

    private static void injectTraceParent(StompHeaderAccessor headerAccessors, TraceContext traceContext) {
        String traceParent = W3C_TRACE_VERSION
                + "-"
                + traceContext.traceId()
                + "-"
                + traceContext.spanId()
                + "-"
                + (Boolean.TRUE.equals(traceContext.sampled()) ? SAMPLED_FLAG : NOT_SAMPLED_FLAG);

        headerAccessors.setNativeHeader(TRACEPARENT_HEADER, traceParent);
    }

    private static Map<String, Object> getSessionAttributes(StompHeaderAccessor headerAccessors) {
        var sessionAttributes = headerAccessors.getSessionAttributes();

        if (sessionAttributes == null) {
            sessionAttributes = new ConcurrentHashMap<>();
            headerAccessors.setSessionAttributes(sessionAttributes);
        }

        return sessionAttributes;
    }
}