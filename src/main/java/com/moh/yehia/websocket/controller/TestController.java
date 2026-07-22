package com.moh.yehia.websocket.controller;

import com.moh.yehia.websocket.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

import java.lang.reflect.Method;
import java.security.Principal;

@Controller
public class TestController {
    private static final Logger log = LoggerFactory.getLogger(TestController.class);
    private final NotificationService notificationService;

    public TestController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @MessageMapping("/notification")
        // FE will need to call this endpoint using /app/notification
    void sendNotification(@Payload String message, SimpMessageHeaderAccessor simpMessageHeaderAccessor, Principal principal) throws InterruptedException, NoSuchMethodException, MethodArgumentNotValidException {
        log.info("Received notification message: {}", message);
        log.info("simpMessageHeaderAccessor: {}", simpMessageHeaderAccessor);
        log.info("principal username: {}", principal.getName());

        String objectName = "sendNotification";

        // 1. Create a BindingResult container for your object
        BindingResult bindingResult = new BeanPropertyBindingResult(message, objectName);

        // get the current method to populate errors
        Method method = this.getClass().getDeclaredMethod(objectName, String.class, SimpMessageHeaderAccessor.class, Principal.class);

        if (message.length() < 5) {
            bindingResult.addError(new FieldError(
                    objectName,           // Object name
                    "message",             // Field name
                    "message length should be greater than 5 chars"     // Error message
            ));
            Message<String> springMessage = MessageBuilder.withPayload(message).build();
            throw new MethodArgumentNotValidException(springMessage, new MethodParameter(method, 0), bindingResult);
        }
        notificationService.sendNotification(message, simpMessageHeaderAccessor, principal.getName());
    }
}
