package com.moh.yehia.websocket.exception;

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

    @MessageExceptionHandler(IllegalStateException.class)
    @SendToUser("/queue/errors")
    public ErrorDTO handleException(Exception e) {
        log.error("Error handling message =>{}", e.getMessage(), e);
        return new ErrorDTO("BAD_REQUEST", e.getMessage(), System.currentTimeMillis());
    }

    @MessageExceptionHandler(AccessDeniedException.class)
    @SendToUser("/queue/errors")
    public ErrorDTO handleAccessDeniedException(Exception e) {
        log.error("Error access denied handling message =>{}", e.getMessage(), e);
        return new ErrorDTO("FORBIDDEN", e.getMessage(), System.currentTimeMillis());
    }

    @MessageExceptionHandler(MethodArgumentNotValidException.class)
    @SendToUser("/queue/errors")
    public ErrorDTO handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        log.error("Error validation handling message =>{}", e.getMessage(), e);
        List<FieldError> fieldErrors = Objects.requireNonNull(e.getBindingResult()).getFieldErrors();
        fieldErrors.forEach(fieldError -> log.info("{} - {}", fieldError.getField(), fieldError.getDefaultMessage()));
        FieldError fieldError = fieldErrors.getFirst();
        return new ErrorDTO("BAD_REQUEST", fieldError.getDefaultMessage(), System.currentTimeMillis());
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser("/queue/errors")
    public ErrorDTO handleGenericException(Exception e) {
        log.error("general application error message =>{}", e.getMessage(), e);
        return new ErrorDTO("INTERNAL_SERVER_ERROR", e.getMessage(), System.currentTimeMillis());
    }
}
