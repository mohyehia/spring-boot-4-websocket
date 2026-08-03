package com.moh.yehia.websocket.config;

import com.moh.yehia.websocket.exception.CustomStompErrorHandler;
import com.moh.yehia.websocket.interceptor.*;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.DefaultContentTypeResolver;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.messaging.access.intercept.AuthorizationChannelInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${websocket.sockjs-enabled}")
    private boolean sockjsEnabled;

    @Value("${websocket.allows-origins}")
    private String allowedOrigins;

    @Value("${websocket.broker.type}")
    private String brokerType;

    @Value("${websocket.broker.relay-host}")
    private String relayHost;

    @Value("${websocket.broker.relay-port}")
    private int relayPort;

    @Value("${websocket.broker.client-login:guest}")
    private String clientLogin;

    @Value("${websocket.broker.client-passcode:guest}")
    private String clientPasscode;

    @Value("${websocket.broker.system-login:guest}")
    private String systemLogin;

    @Value("${websocket.broker.system-passcode:guest}")
    private String systemPasscode;


    private final WebsocketTracingChannelInterceptor websocketTracingChannelInterceptor;
    private final AuthorizationInterceptor authorizationInterceptor;
    private final SecurityContextChannelInterceptor securityContextChannelInterceptor;
    private final AuthorizationChannelInterceptor authorizationChannelInterceptor;
    private final TaskScheduler heartbeatScheduler;
    private final MessagesPerSessionRateLimiterInterceptor messagesPerSessionRateLimiterInterceptor;
    private final SessionLimitPerUserInterceptor sessionLimitPerUserInterceptor;
    private final CustomStompErrorHandler customStompErrorHandler;

    public WebSocketConfig(WebsocketTracingChannelInterceptor websocketTracingChannelInterceptor, AuthorizationInterceptor authorizationInterceptor, SecurityContextChannelInterceptor securityContextChannelInterceptor, AuthorizationChannelInterceptor authorizationChannelInterceptor, TaskScheduler heartbeatScheduler, MessagesPerSessionRateLimiterInterceptor rateLimiterInterceptor, SessionLimitPerUserInterceptor sessionLimitPerUserInterceptor, CustomStompErrorHandler customStompErrorHandler) {
        this.websocketTracingChannelInterceptor = websocketTracingChannelInterceptor;
        this.authorizationInterceptor = authorizationInterceptor;
        this.securityContextChannelInterceptor = securityContextChannelInterceptor;
        this.authorizationChannelInterceptor = authorizationChannelInterceptor;
        this.heartbeatScheduler = heartbeatScheduler;
        this.messagesPerSessionRateLimiterInterceptor = rateLimiterInterceptor;
        this.sessionLimitPerUserInterceptor = sessionLimitPerUserInterceptor;
        this.customStompErrorHandler = customStompErrorHandler;
    }

    @Override
    public void registerStompEndpoints(@NonNull StompEndpointRegistry stompEndpointRegistry) {
        if (sockjsEnabled) {
            stompEndpointRegistry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins).withSockJS();
        } else {
            stompEndpointRegistry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins);
        }

        // register the custom StompErrorHandler
        stompEndpointRegistry.setErrorHandler(customStompErrorHandler);
    }

    @Override
    public void configureMessageBroker(@NonNull MessageBrokerRegistry messageBrokerRegistry) {
        if (brokerType.equalsIgnoreCase(BrokerType.RELAY.name())) {
            messageBrokerRegistry.enableStompBrokerRelay("/topic", "/queue")
                    .setRelayHost(relayHost)
                    .setRelayPort(relayPort)
                    .setClientLogin(clientLogin)
                    .setClientPasscode(clientPasscode)
                    .setSystemLogin(systemLogin)
                    .setSystemPasscode(systemPasscode)
                    .setVirtualHost("/")
                    .setSystemHeartbeatSendInterval(10_000)
                    .setSystemHeartbeatReceiveInterval(10_000)
                    .setTaskScheduler(heartbeatScheduler);
        } else {
            messageBrokerRegistry.enableSimpleBroker("/topic", "/queue")
                    .setHeartbeatValue(new long[]{10_000, 10_000}) // [server-send, server-receive] in ms
                    .setTaskScheduler(heartbeatScheduler);
        }
        messageBrokerRegistry.setApplicationDestinationPrefixes("/app");
        messageBrokerRegistry.setUserDestinationPrefix("/user");
        messageBrokerRegistry.setPreservePublishOrder(true);
    }

    @Override
    public boolean configureMessageConverters(List<MessageConverter> messageConverters) {
        var contentTypeResolver = new DefaultContentTypeResolver();
        contentTypeResolver.setDefaultMimeType(MediaType.APPLICATION_JSON);
        JacksonJsonMessageConverter jacksonJsonMessageConverter = new JacksonJsonMessageConverter();
        jacksonJsonMessageConverter.setContentTypeResolver(contentTypeResolver);
        messageConverters.add(jacksonJsonMessageConverter);
        return false;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(
                authorizationInterceptor,
                securityContextChannelInterceptor,
                sessionLimitPerUserInterceptor,
                authorizationChannelInterceptor,
                messagesPerSessionRateLimiterInterceptor,
                websocketTracingChannelInterceptor
        );
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(websocketTracingChannelInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(64 * 1024); // 64KB max inbound message size => Prevents Client sending huge messages to exhaust server memory
        registry.setSendBufferSizeLimit(512 * 1024);  // 512KB outbound buffer per session => Prevents Slow clients accumulating unsent messages in server memory
        registry.setSendTimeLimit(20_000); // 20s max time to send a message to client => Prevents Hanging connections that never drain their buffer
    }
}
