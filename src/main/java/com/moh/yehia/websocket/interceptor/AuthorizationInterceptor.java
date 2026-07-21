package com.moh.yehia.websocket.interceptor;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class AuthorizationInterceptor implements ChannelInterceptor {
    private static final Logger log = LoggerFactory.getLogger(AuthorizationInterceptor.class);
    private final JwtDecoder jwtDecoder;

    public AuthorizationInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public @Nullable Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        var accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            // Extract token from STOMP native headers
            // Frontend sends: stompClient.connect({'Authorization': 'Bearer <token>'}, ...)
            var authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("Missing or invalid Authorization header on CONNECT");
                throw new IllegalArgumentException("Missing or invalid Authorization header");
            }
            var token = authHeader.substring("Bearer ".length());
            log.debug("Received JWT token (length: {})", token.length());
            try {
                Jwt jwt = jwtDecoder.decode(token);
                var username = jwt.getSubject();
                if (username == null) {
                    throw new IllegalArgumentException("JWT subject (sub) claim is missing");
                }
                log.info("Authenticated websocket username: {}", username);

                // Extract roles from JWT claims
                // Adjust the claim name based on your JWT structure: "roles", "authorities", "scope", etc.
                var roles = jwt.getClaimAsStringList("roles");
                if (roles == null || !roles.contains("USER")) {
                    log.warn("User {} does not have the required USER role", username);
                    throw new IllegalArgumentException("Insufficient permissions: USER role required");
                }
                // populate authorities
                var authorities = roles.stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList();
                // Create authentication and set it as the user on this STOMP session
                var authentication = new UsernamePasswordAuthenticationToken(
                        username,
                        null,
                        authorities
                );
                accessor.setUser(authentication);
                SecurityContextHolder.getContext().setAuthentication(authentication); // needed for the next interceptor

                // to add Extra custom metadata per session using sessionAttributes — optional
                var sessionAttributes = accessor.getSessionAttributes();
                if (sessionAttributes == null) {
                    sessionAttributes = new ConcurrentHashMap<>();
                    accessor.setSessionAttributes(sessionAttributes);
                }
                sessionAttributes.put("username", username);
                sessionAttributes.put("custom_property", "Any custom data to be processed later!");
                sessionAttributes.put("authorities", authorities);

            } catch (JwtException e) {
                log.error("Invalid JWT Token: {}", e.getMessage(), e);
                throw new IllegalArgumentException("Invalid JWT Token", e);
            }
        }
        return message;
    }
}
