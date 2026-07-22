package com.moh.yehia.websocket.exception;

public record ErrorDTO(String code, String message, long timestamp) {
    static ErrorDTO of(String code, String message) {
        return new ErrorDTO(code, message, System.currentTimeMillis());
    }
}
