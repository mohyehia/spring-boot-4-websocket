package com.moh.yehia.websocket.exception;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.util.List;
import java.util.Objects;

@ControllerAdvice
public class WebsocketExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(WebsocketExceptionHandler.class);

    private final Tracer tracer;

    public WebsocketExceptionHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    @MessageExceptionHandler(IllegalStateException.class)
    @SendToUser("/queue/errors")
    public ErrorDTO handleException(Exception e) {
        markCurrentSpanAsError(e, "BAD_REQUEST");
        log.error("Error handling message =>{}", e.getMessage(), e);
        return new ErrorDTO("BAD_REQUEST", e.getMessage(), System.currentTimeMillis());
    }

    @MessageExceptionHandler(AccessDeniedException.class)
    @SendToUser("/queue/errors")
    public ErrorDTO handleAccessDeniedException(Exception e) {
        markCurrentSpanAsError(e, "FORBIDDEN");
        log.error("Error access denied handling message =>{}", e.getMessage(), e);
        return new ErrorDTO("FORBIDDEN", e.getMessage(), System.currentTimeMillis());
    }

    @MessageExceptionHandler(MethodArgumentNotValidException.class)
    @SendToUser("/queue/errors")
    public ErrorDTO handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        List<FieldError> fieldErrors = Objects.requireNonNull(e.getBindingResult()).getFieldErrors();
        fieldErrors.forEach(fieldError -> log.info("{} - {}", fieldError.getField(), fieldError.getDefaultMessage()));
        FieldError fieldError = fieldErrors.getFirst();
        markCurrentSpanAsError(e, "BAD_REQUEST");
        tagCurrentSpan("validation.field", fieldError.getField());
        tagCurrentSpan("validation.message", Objects.toString(fieldError.getDefaultMessage(), "validation failed"));
        log.error("Error validation handling message =>{}", e.getMessage(), e);
        return new ErrorDTO("BAD_REQUEST", fieldError.getDefaultMessage(), System.currentTimeMillis());
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser("/queue/errors")
    public ErrorDTO handleGenericException(Exception e) {
        markCurrentSpanAsError(e, "INTERNAL_SERVER_ERROR");
        log.error("general application error message =>{}", e.getMessage(), e);
        return new ErrorDTO("INTERNAL_SERVER_ERROR", e.getMessage(), System.currentTimeMillis());
    }

    private void markCurrentSpanAsError(Exception exception, String errorCode) {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan == null) {
            return;
        }

        currentSpan.error(exception);
        currentSpan.tag("error", "true");
        currentSpan.tag("error.code", errorCode);
        currentSpan.tag("exception.type", exception.getClass().getName());
        currentSpan.tag("exception.message", Objects.toString(exception.getMessage(), ""));
    }

    private void tagCurrentSpan(String key, String value) {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            currentSpan.tag(key, value);
        }
    }
}
