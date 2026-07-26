package com.moh.yehia.websocket.helper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

public class TestJwtHelper {

    private static final String SECRET = "my_application_super_super_secret_key";

    public static String generateToken(String username, List<String> roles) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());

        long now = Instant.now().getEpochSecond();
        // Properly serialize roles as a JSON array with quoted strings
        String rolesJson = roles.stream()
                .map(r -> "\"" + r + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        String payloadJson = "{\"sub\":\"%s\",\"roles\":%s,\"iat\":%d,\"exp\":%d}"
                .formatted(username, rolesJson, now, now + 86400);
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes());

        String signature = sign(header + "." + payload);
        return header + "." + payload + "." + signature;
    }

    private static String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}