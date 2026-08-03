package com.moh.yehia.websocket.exception;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.AccessDeniedException;

class WebsocketExceptionHandlerTest {
    private WebsocketExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new WebsocketExceptionHandler();
    }

    @Test
    void shouldHandleIllegalStateException() {
        var error = handler.handleException(new IllegalStateException("Invalid state"));
        Assertions.assertThat(error.code()).isEqualTo("BAD_REQUEST");
        Assertions.assertThat(error.message()).isEqualTo("Invalid state");
        Assertions.assertThat(error.timestamp()).isNotNegative();
    }

    @Test
    void shouldHandleAccessDeniedException() {
        var error = handler.handleAccessDeniedException(new AccessDeniedException("Forbidden"));
        Assertions.assertThat(error.code()).isEqualTo("FORBIDDEN");
        Assertions.assertThat(error.message()).isEqualTo("Forbidden");
    }

    @Test
    void shouldHandleGenericException() {
        var error = handler.handleGenericException(new RuntimeException("Oops"));
        Assertions.assertThat(error.code()).isEqualTo("INTERNAL_SERVER_ERROR");
        Assertions.assertThat(error.message()).isEqualTo("Oops");
    }
}
