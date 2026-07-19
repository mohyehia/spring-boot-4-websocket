package com.moh.yehia.websocket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SpringBoot4WebsocketApplication {
    static void main(String[] args) {
        SpringApplication.run(SpringBoot4WebsocketApplication.class, args);
    }
}
