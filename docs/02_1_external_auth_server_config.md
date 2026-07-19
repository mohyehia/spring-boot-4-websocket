# Using Keycloak or an External Auth Server

> This document extends [authentication_authorization.md](authentication_authorization.md). The WebSocket interceptor architecture (interceptor chain, `SecurityContextChannelInterceptor`, `WebsocketSecurityConfig`, `WebSocketConfig`) **remains unchanged**. Only the JWT validation and role extraction need adjustment.

---

## What Changes

| Component          | Self-signed JWT (current)                   | External Auth Server                                              |
|--------------------|---------------------------------------------|-------------------------------------------------------------------|
| `application.yaml` | Custom `jwt.secret-key` property            | Standard `spring.security.oauth2.resourceserver.jwt.*` properties |
| `JwtDecoder` bean  | Manual — `NimbusJwtDecoder.withSecretKey()` | **Auto-configured** by Spring Boot — no bean needed               |
| Algorithm          | HS256 (symmetric secret)                    | RS256 (asymmetric public/private key pair)                        |
| Key management     | You manage the secret                       | Auth server manages keys, exposes JWKS endpoint                   |
| Role extraction    | `jwt.getClaimAsStringList("roles")`         | Provider-specific claim path                                      |

---

## 1. application.yaml — Replace Custom Property

### Option A: Using `issuer-uri` (Recommended)

Auto-discovers the JWKS endpoint via OpenID Connect discovery:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://your-keycloak-host/realms/your-realm
```

Spring Boot automatically:
- Fetches `{issuer-uri}/.well-known/openid-configuration`
- Discovers the JWKS (JSON Web Key Set) URI
- Downloads public keys for RS256 signature validation
- Validates `iss` claim matches the issuer URI

### Option B: Using `jwk-set-uri` (Direct JWKS endpoint)

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: https://your-keycloak-host/realms/your-realm/protocol/openid-connect/certs
```

### Option C: Using a public key file (Offline validation)

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          public-key-location: classpath:public-key.pem
```

---

## 2. Remove the Manual JwtDecoder Bean

With standard Spring Boot properties, the `JwtDecoder` is **auto-configured**. Remove from `SecurityConfig`:

```java
// ❌ REMOVE — no longer needed with issuer-uri or jwk-set-uri
@Bean
JwtDecoder jwtDecoder(@Value("${jwt.secret-key}") String secretKey) {
    SecretKeySpec secret = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    return NimbusJwtDecoder.withSecretKey(secret).build();
}
```

Also remove the custom `jwt.secret-key` property from `application.yaml`.

---

## 3. Role Extraction — Provider-Specific Claim Paths

Each auth server puts roles in different JWT claim locations.

### Keycloak

```json
{
  "sub": "a1b2c3d4-e5f6-...",
  "preferred_username": "moh",
  "realm_access": {
    "roles": ["USER", "ADMIN"]
  },
  "resource_access": {
    "my-client": {
      "roles": ["client-specific-role"]
    }
  }
}
```

```java
var username = jwt.getClaimAsString("preferred_username");
if (username == null) username = jwt.getSubject();

@SuppressWarnings("unchecked")
var realmAccess = (Map<String, Object>) jwt.getClaim("realm_access");
List<String> roles = List.of();
if (realmAccess != null) {
    @SuppressWarnings("unchecked")
    var extracted = (List<String>) realmAccess.get("roles");
    if (extracted != null) roles = extracted;
}
```

### Auth0

```json
{
  "sub": "auth0|123456",
  "permissions": ["read:messages", "write:messages"],
  "https://your-namespace/roles": ["USER", "ADMIN"]
}
```

```java
var username = jwt.getSubject();
var roles = jwt.getClaimAsStringList("https://your-namespace/roles");
// Or use permissions:
var permissions = jwt.getClaimAsStringList("permissions");
```

### Okta

```json
{
  "sub": "moh@example.com",
  "groups": ["USER", "ADMIN"]
}
```

```java
var username = jwt.getSubject();
var roles = jwt.getClaimAsStringList("groups");
```

### AWS Cognito

```json
{
  "sub": "a1b2c3d4-...",
  "cognito:username": "moh",
  "cognito:groups": ["USER", "ADMIN"]
}
```

```java
var username = jwt.getClaimAsString("cognito:username");
var roles = jwt.getClaimAsStringList("cognito:groups");
```

---

## 4. SecurityConfig — Full Keycloak Example

```java
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/public/**").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())
                ));
        return httpSecurity.build();
    }

    // No JwtDecoder bean — auto-configured from issuer-uri

    // Keycloak role mapping for REST endpoints
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            @SuppressWarnings("unchecked")
            var realmAccess = (Map<String, Object>) jwt.getClaim("realm_access");
            if (realmAccess == null) return List.of();
            @SuppressWarnings("unchecked")
            var roles = (List<String>) realmAccess.get("roles");
            if (roles == null) return List.of();
            return roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .collect(Collectors.toList());
        });
        return converter;
    }
}
```

> **Note:** The `JwtAuthenticationConverter` is only for **REST endpoints** (HTTP layer). For WebSocket, the `AuthorizationInterceptor` handles role extraction separately at the STOMP level.

---

## 5. AuthorizationInterceptor — Full Keycloak Example

```java
@Component
public class AuthorizationInterceptor implements ChannelInterceptor {
    private final JwtDecoder jwtDecoder; // Auto-configured by Spring Boot

    public AuthorizationInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public @Nullable Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        var accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            var authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new IllegalArgumentException("Missing or invalid Authorization header");
            }
            var token = authHeader.substring("Bearer ".length());

            Jwt jwt = jwtDecoder.decode(token);

            // Keycloak: preferred_username, fallback to sub (UUID)
            var username = jwt.getClaimAsString("preferred_username");
            if (username == null) {
                username = jwt.getSubject();
            }
            if (username == null) {
                throw new IllegalArgumentException("JWT subject claim is missing");
            }

            // Keycloak: extract realm roles from nested claim
            @SuppressWarnings("unchecked")
            var realmAccess = (Map<String, Object>) jwt.getClaim("realm_access");
            List<String> roles = List.of();
            if (realmAccess != null) {
                @SuppressWarnings("unchecked")
                var extracted = (List<String>) realmAccess.get("roles");
                if (extracted != null) roles = extracted;
            }

            if (!roles.contains("USER")) {
                throw new IllegalArgumentException("Insufficient permissions: USER role required");
            }

            var authorities = roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();

            var authentication = new UsernamePasswordAuthenticationToken(username, null, authorities);
            accessor.setUser(authentication);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        return message;
    }
}
```

---

## 6. Frontend — Obtaining the Token

With an external auth server, the frontend obtains the token via OAuth2 flows (Authorization Code + PKCE):

```javascript
// Keycloak JS adapter
var token = keycloak.token;

// Auth0 SPA SDK
var token = await auth0Client.getTokenSilently();

// Then connect:
var socket = new SockJS('http://localhost:8080/ws');
var stompClient = Stomp.over(socket);
stompClient.connect(
    { 'Authorization': 'Bearer ' + token },
    function (frame) {
        console.log('Connected: ' + frame);
        stompClient.subscribe('/user/queue/notifications', function (msg) {
            console.log(msg.body);
        });
    }
);
```

---

## Summary — What Changes vs What Stays

| Aspect                                        | Change                                                                               |
|-----------------------------------------------|--------------------------------------------------------------------------------------|
| `application.yaml`                            | Replace `jwt.secret-key` with `spring.security.oauth2.resourceserver.jwt.issuer-uri` |
| `JwtDecoder` bean                             | **Remove** — auto-configured by Spring Boot                                          |
| Algorithm                                     | HS256 → RS256 (asymmetric)                                                           |
| Role extraction in `AuthorizationInterceptor` | Adjust claim path for your provider                                                  |
| `SecurityFilterChain`                         | Optionally add `JwtAuthenticationConverter` for REST endpoint role mapping           |
| `WebsocketSecurityConfig`                     | **No change**                                                                        |
| `SecurityContextChannelInterceptor`           | **No change**                                                                        |
| `WebSocketConfig`                             | **No change**                                                                        |
| Frontend                                      | Obtain token from OAuth2 flow instead of manual generation                           |

