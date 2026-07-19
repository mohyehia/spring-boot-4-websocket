package com.moh.yehia.websocket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.messaging.access.intercept.AuthorizationChannelInterceptor;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

@Configuration
public class WebsocketSecurityConfig {

    @Bean
    AuthorizationManager<Message<?>> messageAuthorizationManager() {
        var builder = MessageMatcherDelegatingAuthorizationManager.builder();
        builder
                .simpTypeMatchers(
                        SimpMessageType.CONNECT_ACK,
                        SimpMessageType.DISCONNECT,
                        SimpMessageType.DISCONNECT_ACK,
                        SimpMessageType.UNSUBSCRIBE,
                        SimpMessageType.HEARTBEAT,
                        SimpMessageType.OTHER
                )
                .permitAll()
                .simpTypeMatchers(SimpMessageType.CONNECT)
                .authenticated()
                .simpDestMatchers("/app/**").authenticated()
                .simpSubscribeDestMatchers("/user/queue/**").authenticated()
                .simpSubscribeDestMatchers("/topic/**").authenticated()
                .anyMessage()
                .denyAll();
        return builder.build();
    }

    @Bean
    AuthorizationChannelInterceptor authorizationChannelInterceptor(AuthorizationManager<Message<?>> messageAuthorizationManager) {
        return new AuthorizationChannelInterceptor(messageAuthorizationManager);
    }
}
