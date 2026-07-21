package com.moh.yehia.websocket.interceptor;

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

    // The below code is for extracting traceparent from FE if they are using Opentelemetry JS
    private static final TextMapGetter<StompHeaderAccessor> STOMP_HEADER_ACCESSOR_TEXT_MAP_GETTER = new TextMapGetter<StompHeaderAccessor>() {

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
