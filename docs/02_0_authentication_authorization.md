# WebSocket Authentication & Authorization with JWT — Spring Boot 4

## Table of Contents

- [Overview](#overview)
- [High-Level Design (HLD)](#high-level-design-hld)
- [Architecture Layers](#architecture-layers)
- [Dependencies](#dependencies)
- [Configuration](#configuration)
  - [application.yaml](#applicationyaml)
  - [SecurityConfig — HTTP Security & JWT Decoder](#securityconfig--http-security--jwt-decoder)
  - [AuthorizationInterceptor — JWT Authentication on STOMP CONNECT](#authorizationinterceptor--jwt-authentication-on-stomp-connect)
  - [SecurityContextChannelInterceptor — SecurityContext Bridge](#securitycontextchannelinterceptor--securitycontext-bridge)
  - [WebsocketSecurityConfig — STOMP Message-Level Authorization](#websocketsecurityconfig--stomp-message-level-authorization)
  - [WebSocketConfig — Interceptor Registration](#websocketconfig--interceptor-registration)
- [Interceptor Chain — Execution Order](#interceptor-chain--execution-order)
- [Authentication Flow](#authentication-flow)
- [Authorization Rules](#authorization-rules)
- [JWT Token Structure](#jwt-token-structure)
- [Frontend Integration](#frontend-integration)
- [Using Principal in Controllers](#using-principal-in-controllers)
- [Session Attributes](#session-attributes)
- [CSRF Protection](#csrf-protection)
- [Security Best Practices](#security-best-practices)
- [Common Errors & Solutions](#common-errors--solutions)
- [Generating Test JWT Tokens](#generating-test-jwt-tokens)

> **See also:** For using an external auth server (Keycloak, Auth0, Okta, AWS Cognito), see [external_auth_server_config.md](external_auth_server_config.md).

---

## Overview

WebSocket connections in Spring Boot use a **two-layer security model**:

1. **HTTP Layer** — The initial WebSocket handshake is an HTTP request. Spring Security's `SecurityFilterChain` handles this.
2. **STOMP Layer** — After the handshake, all communication happens over STOMP frames. Authentication and authorization are enforced via `ChannelInterceptor`s on the inbound message channel.

**Key decision:** Authentication happens at the **STOMP CONNECT** frame level (not the HTTP handshake), using a JWT Bearer token passed in STOMP native headers.

---

## High-Level Design (HLD)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLIENT (Browser)                               │
│                                                                             │
│  1. Open SockJS connection to /ws (HTTP handshake — no auth required)       │
│  2. Send STOMP CONNECT with header: Authorization: Bearer <JWT>             │
│  3. Subscribe to /user/queue/notifications                                  │
│  4. Send messages to /app/notification                                      │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │
                                   ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                           SPRING BOOT SERVER                                │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐     │
│  │                     HTTP SECURITY LAYER                             │     │
│  │  SecurityFilterChain:                                               │     │
│  │    /ws/**     → permitAll() (handshake allowed without auth)        │     │
│  │    /public/** → permitAll()                                         │     │
│  │    /**        → authenticated() (JWT for REST endpoints)            │     │
│  │  CSRF: disabled (stateless JWT — no cookies)                        │     │
│  └─────────────────────────────────────────────────────────────────────┘     │
│                                   │                                          │
│                                   ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐     │
│  │               STOMP INBOUND CHANNEL INTERCEPTORS                    │     │
│  │  (Executed in order for every inbound STOMP frame)                   │     │
│  │                                                                     │     │
│  │  1. AuthorizationInterceptor                                        │     │
│  │     └─ On CONNECT: extract JWT → decode → validate roles            │     │
│  │        → set Principal via accessor.setUser()                       │     │
│  │        → set SecurityContextHolder                                  │     │
│  │        → store session attributes                                   │     │
│  │                                                                     │     │
│  │  2. SecurityContextChannelInterceptor                               │     │
│  │     └─ For ALL frames: read Principal from message headers          │     │
│  │        → populate SecurityContextHolder                             │     │
│  │        → clear after completion (prevent thread-local leaks)        │     │
│  │                                                                     │     │
│  │  3. AuthorizationChannelInterceptor                                 │     │
│  │     └─ Check message type & destination against rules:              │     │
│  │        CONNECT → authenticated()                                    │     │
│  │        SEND to /app/** → authenticated()                            │     │
│  │        SUBSCRIBE to /user/queue/** → authenticated()                │     │
│  │        SUBSCRIBE to /topic/** → authenticated()                     │     │
│  │        HEARTBEAT, DISCONNECT, etc. → permitAll()                    │     │
│  │        Everything else → denyAll()                                  │     │
│  │                                                                     │     │
│  │  4. WebsocketTracingChannelInterceptor                              │     │
│  │     └─ Create observation, attach traceId/spanId                    │     │
│  └─────────────────────────────────────────────────────────────────────┘     │
│                                   │                                          │
│                                   ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐     │
│  │                    @MessageMapping HANDLERS                         │     │
│  │  TestController.sendNotification(message, principal)                 │     │
│  │    → principal.getName() returns authenticated username              │     │
│  │    → convertAndSendToUser(username, destination, payload)           │     │
│  └─────────────────────────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Architecture Layers

| Layer | Component | Responsibility |
|-------|-----------|----------------|
| HTTP | `SecurityConfig` | Permit WebSocket handshake, protect REST endpoints, configure JWT decoder |
| STOMP Auth | `AuthorizationInterceptor` | Extract & validate JWT on CONNECT, set Principal |
| STOMP Bridge | `SecurityContextChannelInterceptor` | Propagate Principal to SecurityContextHolder for all frames |
| STOMP AuthZ | `WebsocketSecurityConfig` + `AuthorizationChannelInterceptor` | Enforce destination-level access rules |
| Application | `TestController` | Use `Principal` to identify authenticated users |

---

## Dependencies

```xml
<!-- Spring Security -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- OAuth2 Resource Server (JWT support) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
</dependency>

<!-- Spring Security Messaging (STOMP-level authorization) -->
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-messaging</artifactId>
</dependency>
```

---

## Configuration

### application.yaml

```yaml
# Custom property — NOT a standard Spring Boot property
# Must be at least 32 characters for HS256
jwt:
  secret-key: my_application_super_super_secret_key
```

> **Note:** `spring.security.oauth2.resourceserver.jwt.secret-key` does NOT exist in Spring Boot auto-configuration. For HMAC (symmetric) JWT, you must configure the `JwtDecoder` bean manually.

---

### SecurityConfig — HTTP Security & JWT Decoder

```java
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
        // CSRF disabled — not needed for stateless JWT authentication (no cookies used)
        httpSecurity.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/public/**").permitAll()
                        .requestMatchers("/ws/**").permitAll() // Handshake allowed, auth at STOMP level
                        .anyRequest().authenticated()
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(_ -> {}));
        return httpSecurity.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${jwt.secret-key}") String secretKey) {
        SecretKeySpec secret = new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"
        );
        return NimbusJwtDecoder.withSecretKey(secret).build();
    }
}
```

**Key points:**
- `/ws/**` is `permitAll()` — the HTTP handshake has no auth; authentication is enforced at the STOMP CONNECT level
- `JwtDecoder` uses HMAC-SHA256 (symmetric key) — suitable for single-service setups
- No `UserDetailsService` or `PasswordEncoder` needed — purely JWT-based

---

### AuthorizationInterceptor — JWT Authentication on STOMP CONNECT

```java
@Component
public class AuthorizationInterceptor implements ChannelInterceptor {
    private final JwtDecoder jwtDecoder;

    public AuthorizationInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public @Nullable Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        var accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            // 1. Extract Bearer token from STOMP native headers
            var authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new IllegalArgumentException("Missing or invalid Authorization header");
            }
            var token = authHeader.substring("Bearer ".length());

            // 2. Decode and validate JWT
            Jwt jwt = jwtDecoder.decode(token);
            var username = jwt.getSubject();
            if (username == null) {
                throw new IllegalArgumentException("JWT subject (sub) claim is missing");
            }

            // 3. Validate roles
            var roles = jwt.getClaimAsStringList("roles");
            if (roles == null || !roles.contains("USER")) {
                throw new IllegalArgumentException("Insufficient permissions: USER role required");
            }

            // 4. Create authentication token
            var authorities = roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
            var authentication = new UsernamePasswordAuthenticationToken(username, null, authorities);

            // 5. Set Principal on the STOMP session (persists across all frames)
            accessor.setUser(authentication);

            // 6. Set SecurityContextHolder (needed for AuthorizationChannelInterceptor)
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // 7. Optional: store custom session attributes
            var sessionAttributes = accessor.getSessionAttributes();
            if (sessionAttributes == null) {
                sessionAttributes = new ConcurrentHashMap<>();
                accessor.setSessionAttributes(sessionAttributes);
            }
            sessionAttributes.put("username", username);
            sessionAttributes.put("authorities", authorities);
        }
        return message;
    }
}
```

**What `accessor.setUser()` does:**
- Associates the `Principal` with the entire WebSocket session
- Every subsequent STOMP frame (SEND, SUBSCRIBE, etc.) on that session automatically carries this `Principal`
- Handled internally by Spring's `StompSubProtocolHandler`

---

### SecurityContextChannelInterceptor — SecurityContext Bridge

```java
@Component
public class SecurityContextChannelInterceptor implements ChannelInterceptor {

    @Override
    public @Nullable Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        var accessor = MessageHeaderAccessor.getAccessor(message, SimpMessageHeaderAccessor.class);
        if (accessor != null && accessor.getUser() instanceof UsernamePasswordAuthenticationToken auth) {
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        return message;
    }

    @Override
    public void afterSendCompletion(@NonNull Message<?> message, @NonNull MessageChannel channel,
                                    boolean sent, @Nullable Exception ex) {
        SecurityContextHolder.clearContext();
    }
}
```

**Why this is needed:**
- `AuthorizationChannelInterceptor` reads authentication from `SecurityContextHolder`
- STOMP messages run on different threads where `SecurityContextHolder` is empty
- This interceptor bridges the gap: reads `Principal` from message headers → populates `SecurityContextHolder`
- `afterSendCompletion` clears the context to prevent thread-local leaks

---

### WebsocketSecurityConfig — STOMP Message-Level Authorization

```java
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
    AuthorizationChannelInterceptor authorizationChannelInterceptor(
            AuthorizationManager<Message<?>> messageAuthorizationManager) {
        return new AuthorizationChannelInterceptor(messageAuthorizationManager);
    }
}
```

**Why not `@EnableWebSocketSecurity`?**
- `@EnableWebSocketSecurity` automatically enables **CSRF protection for STOMP messages**, which requires a CSRF token in the CONNECT frame headers
- Since we use JWT (not cookies), CSRF is unnecessary and causes `CloseStatus[code=1002]` errors
- Manual registration gives full control without CSRF interference

**Why `CONNECT` is `authenticated()` not `permitAll()`:**
- `AuthorizationInterceptor` (step 1) validates the JWT and sets the Principal
- `SecurityContextChannelInterceptor` (step 2) populates SecurityContextHolder
- `AuthorizationChannelInterceptor` (step 3) checks `.authenticated()` — which passes because SecurityContext is already set
- This provides **defense in depth** — two layers both guard CONNECT

---

### WebSocketConfig — Interceptor Registration

```java
@Override
public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(
            authorizationInterceptor,           // 1st: JWT → set Principal
            securityContextChannelInterceptor,  // 2nd: Principal → SecurityContextHolder
            authorizationChannelInterceptor,    // 3rd: check destination permissions
            websocketTracingChannelInterceptor  // 4th: trace
    );
}
```

**Order matters!** Each interceptor depends on the previous one's work.

---

## Interceptor Chain — Execution Order

For every inbound STOMP frame, interceptors execute in this order:

```
Message arrives
    │
    ▼
┌──────────────────────────────┐
│ 1. AuthorizationInterceptor │  Only acts on CONNECT:
│    (ChannelInterceptor)      │  Extract JWT → decode → validate → setUser()
└──────────────┬───────────────┘
               │
               ▼
┌──────────────────────────────────────┐
│ 2. SecurityContextChannelInterceptor │  For ALL frames:
│    (ChannelInterceptor)              │  Read Principal → set SecurityContextHolder
└──────────────┬───────────────────────┘
               │
               ▼
┌──────────────────────────────────────┐
│ 3. AuthorizationChannelInterceptor   │  For ALL frames:
│    (Spring Security)                 │  Check message type/destination rules
└──────────────┬───────────────────────┘
               │
               ▼
┌──────────────────────────────────────────┐
│ 4. WebsocketTracingChannelInterceptor    │  Create Observation,
│    (ExecutorChannelInterceptor)           │  attach traceId/spanId
└──────────────┬───────────────────────────┘
               │
               ▼
         @MessageMapping handler
```

---

## Authentication Flow

```
┌──────────┐     SockJS HTTP Handshake      ┌──────────────┐
│  Client  │ ────────────────────────────▶   │ Spring Boot  │
│          │     /ws (permitAll)             │              │
│          │                                 │              │
│          │     STOMP CONNECT               │              │
│          │     Authorization: Bearer JWT   │              │
│          │ ────────────────────────────▶   │              │
│          │                                 │ Decode JWT   │
│          │                                 │ Validate sub │
│          │                                 │ Check roles  │
│          │                                 │ Set Principal│
│          │     CONNECTED                   │              │
│          │ ◀────────────────────────────   │              │
│          │                                 │              │
│          │     SUBSCRIBE                   │              │
│          │     /user/queue/notifications   │              │
│          │ ────────────────────────────▶   │ Check:       │
│          │                                 │ authenticated│
│          │     SEND                        │              │
│          │     /app/notification           │              │
│          │ ────────────────────────────▶   │ Check:       │
│          │                                 │ authenticated│
│          │     MESSAGE                     │              │
│          │     /user/queue/notifications   │              │
│          │ ◀────────────────────────────   │ Send to user │
└──────────┘                                 └──────────────┘
```

---

## Authorization Rules

| Message Type     | Destination      | Rule              | Reason                                            |
|------------------|------------------|-------------------|---------------------------------------------------|
| `CONNECT`        | —                | `authenticated()` | Must have valid JWT                               |
| `CONNECT_ACK`    | —                | `permitAll()`     | Internal server response                          |
| `DISCONNECT`     | —                | `permitAll()`     | Allow graceful disconnect                         |
| `DISCONNECT_ACK` | —                | `permitAll()`     | Internal server response                          |
| `UNSUBSCRIBE`    | —                | `permitAll()`     | Allow unsubscribing                               |
| `HEARTBEAT`      | —                | `permitAll()`     | Keep-alive frames                                 |
| `OTHER`          | —                | `permitAll()`     | Internal Spring messages                          |
| `MESSAGE` (SEND) | `/app/**`        | `authenticated()` | Only authenticated users can send                 |
| `SUBSCRIBE`      | `/user/queue/**` | `authenticated()` | Only authenticated users receive private messages |
| `SUBSCRIBE`      | `/topic/**`      | `authenticated()` | Only authenticated users receive broadcasts       |
| Everything else  | —                | `denyAll()`       | Block by default                                  |

---

## JWT Token Structure

```json
{
  "sub": "moh",
  "name": "Mohammed",
  "roles": ["USER"],
  "iat": 1752940800,
  "exp": 1784505600
}
```

| Claim   | Usage                                                                         |
|---------|-------------------------------------------------------------------------------|
| `sub`   | Username — becomes `Principal.getName()`                                      |
| `roles` | Array of roles — validated on CONNECT, mapped to `ROLE_` prefixed authorities |
| `iat`   | Issued at — validated by `JwtDecoder`                                         |
| `exp`   | Expiration — validated by `JwtDecoder` (rejects expired tokens)               |

**Role claim name:** Adjust `jwt.getClaimAsStringList("roles")` based on your JWT provider:
- Custom JWT: `"roles"`
- Keycloak: `"realm_access.roles"`
- Auth0: `"permissions"` or custom namespace
- OAuth2 standard: `"scope"`

---

## Frontend Integration

### Connecting with JWT

```javascript
var socket = new SockJS('http://localhost:8080/ws');
var stompClient = Stomp.over(socket);

stompClient.connect(
    { 'Authorization': 'Bearer ' + jwtToken },  // Sent as STOMP CONNECT header
    function (frame) {
        console.log('Connected: ' + frame);

        // Subscribe to private notifications
        stompClient.subscribe('/user/queue/notifications', function (msg) {
            console.log('Received: ' + msg.body);
        });
    },
    function (error) {
        console.error('Connection error: ' + error);
    }
);
```

### Sending a message

```javascript
stompClient.send('/app/notification', {}, JSON.stringify({ content: 'Hello!' }));
```

---

## Using Principal in Controllers

With authentication in place, `@MessageMapping` methods receive a `Principal` automatically:

```java
@MessageMapping("/notification")
void sendNotification(@Payload String message, Principal principal) {
    String username = principal.getName(); // "moh" — from JWT sub claim
    log.info("Received from user: {}, message: {}", username, message);

    // Send private notification — Spring resolves username to session(s)
    simpMessagingTemplate.convertAndSendToUser(
            username,
            "/queue/notifications",
            "Reply: " + message
    );
}
```

**Before (without auth):** Used sessionId — fragile, insecure, breaks with multiple tabs.

**After (with auth):** Use `principal.getName()` — stable, secure, works across multiple sessions for the same user.

---

## Session Attributes

Optional custom metadata stored per WebSocket session:

```java
// Set during CONNECT (in AuthorizationInterceptor):
sessionAttributes.put("username", username);
sessionAttributes.put("custom_property", "Any custom data");
sessionAttributes.put("authorities", authorities);

// Read in @MessageMapping methods:
@MessageMapping("/notification")
void handle(SimpMessageHeaderAccessor headerAccessor) {
    String username = (String) headerAccessor.getSessionAttributes().get("username");
}
```

| Mechanism            | Use for                                          |
|----------------------|--------------------------------------------------|
| `accessor.setUser()` | Identity & authorization — **required**          |
| `sessionAttributes`  | Extra custom metadata per session — **optional** |

---

## CSRF Protection

| Layer                             | Status           | Reason                                                                                                        |
|-----------------------------------|------------------|---------------------------------------------------------------------------------------------------------------|
| HTTP (`SecurityConfig`)           | **Disabled**     | Stateless JWT — no cookies used. CSRF attacks exploit cookie-based auth, which doesn't apply here.            |
| STOMP (`WebsocketSecurityConfig`) | **Not enforced** | No `@EnableWebSocketSecurity` (which would auto-enable CSRF). JWT in STOMP headers is not vulnerable to CSRF. |

### When you SHOULD enable CSRF:
- Cookie-based session authentication
- Login form served by the app
- JWT stored in HTTP-only cookies

---

## Security Best Practices

| Practice                                     | Status                                                      |
|----------------------------------------------|-------------------------------------------------------------|
| ✅ Never log raw JWT tokens                   | Use `log.debug("token length: {}", token.length())`         |
| ✅ Validate JWT `sub` claim is not null       | Explicit null check with descriptive error                  |
| ✅ Validate roles before setting Principal    | Reject users without required roles                         |
| ✅ Use proper null checks, not `assert`       | `assert` is disabled at runtime by default                  |
| ✅ Clear SecurityContextHolder after use      | Prevents thread-local leaks via `afterSendCompletion`       |
| ✅ Use `StandardCharsets.UTF_8` for key bytes | Avoids platform-dependent encoding                          |
| ✅ Secret key ≥ 32 characters                 | Required for HS256 algorithm                                |
| ✅ Defense in depth                           | JWT auth (interceptor 1) + destination auth (interceptor 3) |
| ✅ Deny all by default                        | `anyMessage().denyAll()` — whitelist approach               |

---

## Common Errors & Solutions

### `CloseStatus[code=1002, reason=null]` — Protocol Error

**Cause 1:** `@EnableWebSocketSecurity` enforces CSRF for STOMP, and no CSRF token is provided.
**Solution:** Don't use `@EnableWebSocketSecurity`. Manually register `AuthorizationChannelInterceptor`.

**Cause 2:** `AuthorizationChannelInterceptor` denies a message type not covered in the rules (e.g., `CONNECT_ACK`, `HEARTBEAT`).
**Solution:** Explicitly permit all internal message types: `CONNECT_ACK`, `DISCONNECT_ACK`, `HEARTBEAT`, `OTHER`.

### `Bean of type MessageMatcherDelegatingAuthorizationManager.Builder not found`

**Cause:** The `Builder` is only auto-registered when using `@EnableWebSocketSecurity`.
**Solution:** Create it manually: `MessageMatcherDelegatingAuthorizationManager.builder()`.

### `AccessDeniedException` on SUBSCRIBE/SEND after successful CONNECT

**Cause:** `AuthorizationChannelInterceptor` reads authentication from `SecurityContextHolder`, which is empty on STOMP message threads.
**Solution:** Add `SecurityContextChannelInterceptor` to bridge the Principal from message headers to `SecurityContextHolder`.

### `Missing or invalid Authorization header`

**Cause:** Frontend not sending the `Authorization` header in the STOMP CONNECT frame.
**Solution:** Pass it in the first argument of `stompClient.connect()`:
```javascript
stompClient.connect({ 'Authorization': 'Bearer ' + token }, callback);
```

---

## Generating Test JWT Tokens

### Option 1: jwt.io

Go to [jwt.io](https://jwt.io), set algorithm to HS256, paste payload and secret.

### Option 2: Python

```bash
pip install PyJWT
python -c "import jwt, time; print(jwt.encode({'sub': 'moh', 'roles': ['USER'], 'iat': int(time.time()), 'exp': int(time.time()) + 86400}, 'my_application_super_super_secret_key', algorithm='HS256'))"
```

### Option 3: Node.js

```bash
node -e "const crypto = require('crypto'); const h = Buffer.from(JSON.stringify({alg:'HS256',typ:'JWT'})).toString('base64url'); const p = Buffer.from(JSON.stringify({sub:'moh',roles:['USER'],iat:Math.floor(Date.now()/1000),exp:Math.floor(Date.now()/1000)+86400})).toString('base64url'); const s = crypto.createHmac('sha256','my_application_super_super_secret_key').update(h+'.'+p).digest('base64url'); console.log(h+'.'+p+'.'+s)"
```

### Option 4: REST endpoint (dev only)

Add a `/public/token` endpoint using the `jjwt` library — see project README for details.


