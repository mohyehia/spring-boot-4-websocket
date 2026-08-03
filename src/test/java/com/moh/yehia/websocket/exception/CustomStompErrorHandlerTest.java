package com.moh.yehia.websocket.exception;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomStompErrorHandlerTest {
    private CustomStompErrorHandler errorHandler;

    @BeforeEach
    void setUp() {
        errorHandler = new CustomStompErrorHandler();
    }

    @Test
    void shouldHandleAuthFailure() {
        var cause = new IllegalArgumentException("Invalid JWT Token");
        var wrapper = new org.springframework.messaging.MessageDeliveryException(
                MessageBuilder.withPayload(new byte[0]).build(), "Failed", cause);

        var result = errorHandler.handleClientMessageProcessingError(
                MessageBuilder.withPayload(new byte[0]).build(), wrapper);

        Assertions.assertThat(result).isNotNull();
        var body = new String(result.getPayload());
        assertTrue(body.contains("Authentication failed") || body.contains("Invalid JWT"));
    }

    @Test
    void shouldHandleRateLimitError() {
        var cause = new IllegalStateException("Rate limit exceeded");
        var wrapper = new org.springframework.messaging.MessageDeliveryException(
                MessageBuilder.withPayload(new byte[0]).build(), "Failed", cause);

        var result = errorHandler.handleClientMessageProcessingError(
                MessageBuilder.withPayload(new byte[0]).build(), wrapper);

        Assertions.assertThat(result).isNotNull();
        var body = new String(result.getPayload());
        Assertions.assertThat(body).contains("Rate limit exceeded");
    }

    @Test
    void shouldHandleGenericError() {
        var cause = new RuntimeException("Something unexpected");
        var wrapper = new org.springframework.messaging.MessageDeliveryException(
                MessageBuilder.withPayload(new byte[0]).build(), "Failed", cause);

        var result = errorHandler.handleClientMessageProcessingError(
                MessageBuilder.withPayload(new byte[0]).build(), wrapper);

        Assertions.assertThat(result).isNotNull();
        // Should NOT leak internal error details
        var body = new String(result.getPayload());
        assertFalse(body.contains("Something unexpected"));
    }
}
