package com.moh.yehia.websocket.config;

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

import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final WebsocketTracingChannelInterceptor websocketTracingChannelInterceptor;
    private final AuthorizationInterceptor authorizationInterceptor;
    private final SecurityContextChannelInterceptor securityContextChannelInterceptor;
    private final AuthorizationChannelInterceptor authorizationChannelInterceptor;
    private final TaskScheduler heartbeatScheduler;

    public WebSocketConfig(WebsocketTracingChannelInterceptor websocketTracingChannelInterceptor, AuthorizationInterceptor authorizationInterceptor, SecurityContextChannelInterceptor securityContextChannelInterceptor, AuthorizationChannelInterceptor authorizationChannelInterceptor, TaskScheduler heartbeatScheduler) {
        this.websocketTracingChannelInterceptor = websocketTracingChannelInterceptor;
        this.authorizationInterceptor = authorizationInterceptor;
        this.securityContextChannelInterceptor = securityContextChannelInterceptor;
        this.authorizationChannelInterceptor = authorizationChannelInterceptor;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry stompEndpointRegistry) {
        stompEndpointRegistry.addEndpoint("/ws").setAllowedOrigins("http://127.0.0.1:57391").withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry messageBrokerRegistry) {
        messageBrokerRegistry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{10_000, 10_000}) // [server-send, server-receive] in ms
                .setTaskScheduler(heartbeatScheduler);
        messageBrokerRegistry.setApplicationDestinationPrefixes("/app");
        messageBrokerRegistry.setUserDestinationPrefix("/user");
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
        registration.interceptors(authorizationInterceptor, securityContextChannelInterceptor, authorizationChannelInterceptor, websocketTracingChannelInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(websocketTracingChannelInterceptor);
    }
}
