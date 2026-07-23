# SockJS Fallback Considerations

## Overview

SockJS is a fallback transport layer. When a browser or network doesn't support native WebSocket (corporate proxies, old browsers, restrictive firewalls), SockJS falls back to alternative transports:

1. **WebSocket** (preferred)
2. **XHR-streaming**
3. **XHR-polling**
4. **EventSource (SSE)**

---

## Do You Need SockJS?

| Scenario                                                      | Need SockJS?                        |
|---------------------------------------------------------------|-------------------------------------|
| Modern browsers only (Chrome, Firefox, Edge, Safari)          | ❌ No — all support native WebSocket |
| Clients behind corporate proxies that block WebSocket upgrade | ✅ Yes                               |
| Mobile apps (native Android/iOS)                              | ❌ No — use native WebSocket         |
| Must support IE11 or very old browsers                        | ✅ Yes                               |
| Behind a load balancer that supports WebSocket                | ❌ No                                |
| Public-facing app (unknown client environments)               | ✅ Yes (safety net)                  |

**For most modern apps, you don't need SockJS.** Native WebSocket is supported everywhere.

---

## Configuration Options

### Option 1: SockJS Only

```java
@Override
public void registerStompEndpoints(StompEndpointRegistry stompEndpointRegistry) {
    stompEndpointRegistry.addEndpoint("/ws")
            .setAllowedOrigins("https://yourdomain.com")
            .withSockJS();
}
```

### Option 2: Native WebSocket Only

```java
@Override
public void registerStompEndpoints(StompEndpointRegistry stompEndpointRegistry) {
    stompEndpointRegistry.addEndpoint("/ws")
            .setAllowedOrigins("https://yourdomain.com");
    // No .withSockJS()
}
```

### Option 3: Both (Best of Both Worlds)

```java
@Override
public void registerStompEndpoints(StompEndpointRegistry stompEndpointRegistry) {
    // Native WebSocket — preferred
    stompEndpointRegistry.addEndpoint("/ws")
            .setAllowedOrigins("https://yourdomain.com");

    // SockJS fallback — for clients that can't use WebSocket
    stompEndpointRegistry.addEndpoint("/ws-fallback")
            .setAllowedOrigins("https://yourdomain.com")
            .withSockJS();
}
```

### Option 4: Configurable via application.yaml

```yaml
websocket:
  sockjs-enabled: false
  allowed-origins: https://yourdomain.com
```

```java
@Value("${websocket.sockjs-enabled:false}")
private boolean sockjsEnabled;

@Value("${websocket.allowed-origins}")
private String allowedOrigins;

@Override
public void registerStompEndpoints(StompEndpointRegistry stompEndpointRegistry) {
    if (sockjsEnabled) {
        stompEndpointRegistry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins).withSockJS();
    } else {
        stompEndpointRegistry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins);
    }
}
```

---

## Frontend Connection

### With SockJS:

```javascript
var socket = new SockJS('https://yourdomain.com/ws');
var stompClient = Stomp.over(socket);
stompClient.connect({'Authorization': 'Bearer ' + token}, onConnected, onError);
```

### Without SockJS (Native WebSocket):

```javascript
var socket = new WebSocket('wss://yourdomain.com/ws');
var stompClient = Stomp.over(socket);
stompClient.connect({'Authorization': 'Bearer ' + token}, onConnected, onError);
```

### With fallback logic:

```javascript
function connect() {
    try {
        var socket = new WebSocket('wss://yourdomain.com/ws');
        var stompClient = Stomp.over(socket);
        stompClient.connect({...}, onConnected, function() {
            connectWithSockJS();
        });
    } catch (e) {
        connectWithSockJS();
    }
}

function connectWithSockJS() {
    var socket = new SockJS('https://yourdomain.com/ws-fallback');
    var stompClient = Stomp.over(socket);
    stompClient.connect({...}, onConnected, onError);
}
```

---

## Kubernetes Ingress Configuration

### With SockJS — Full Config

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: websocket-ingress
  annotations:
    # Sticky sessions — REQUIRED for SockJS fallback transports
    nginx.ingress.kubernetes.io/affinity: "cookie"
    nginx.ingress.kubernetes.io/session-cookie-name: "WEBSOCKET_AFFINITY"
    nginx.ingress.kubernetes.io/session-cookie-expires: "172800"
    nginx.ingress.kubernetes.io/session-cookie-max-age: "172800"
    nginx.ingress.kubernetes.io/session-cookie-path: "/ws"

    # WebSocket upgrade support
    nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "3600"
    nginx.ingress.kubernetes.io/proxy-http-version: "1.1"
    nginx.ingress.kubernetes.io/proxy-buffering: "off"
    nginx.ingress.kubernetes.io/configuration-snippet: |
      proxy_set_header Upgrade $http_upgrade;
      proxy_set_header Connection "upgrade";

    # TLS
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
spec:
  ingressClassName: nginx
  tls:
    - hosts:
        - ws.yourdomain.com
      secretName: websocket-tls-secret
  rules:
    - host: ws.yourdomain.com
      http:
        paths:
          - path: /ws
            pathType: Prefix    # MUST be Prefix — SockJS uses sub-paths like /ws/info, /ws/123/abc/xhr
            backend:
              service:
                name: websocket-service
                port:
                  number: 8080
```

### Without SockJS — Minimal Config

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: websocket-ingress
  annotations:
    # WebSocket upgrade support
    nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "3600"
    nginx.ingress.kubernetes.io/proxy-http-version: "1.1"
    nginx.ingress.kubernetes.io/proxy-buffering: "off"
    nginx.ingress.kubernetes.io/configuration-snippet: |
      proxy_set_header Upgrade $http_upgrade;
      proxy_set_header Connection "upgrade";

    # TLS
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
spec:
  ingressClassName: nginx
  tls:
    - hosts:
        - ws.yourdomain.com
      secretName: websocket-tls-secret
  rules:
    - host: ws.yourdomain.com
      http:
        paths:
          - path: /ws
            pathType: Exact
            backend:
              service:
                name: websocket-service
                port:
                  number: 8080
```

---

## Comparison Table — With vs Without SockJS

| Concern               | With SockJS                                | Without SockJS |
|-----------------------|--------------------------------------------|----------------|
| Sticky sessions       | ✅ Required                                 | ❌ Not needed   |
| `pathType`            | `Prefix` (sub-paths)                       | `Exact`        |
| LB complexity         | Higher                                     | Lower          |
| Server load           | Higher (polling fallback)                  | Lower          |
| Latency               | Higher (polling delay)                     | Lower (~1ms)   |
| Browser support       | Universal                                  | Modern only    |
| CORS complexity       | Multiple sub-paths + credentials           | Single path    |
| Cookie-based affinity | Required (can't use `allowedOrigins("*")`) | Not needed     |
| Infrastructure cost   | Higher                                     | Lower          |

---

## SockJS-Specific Concerns

### 1. Sticky Sessions Are Mandatory

SockJS fallback transports use multiple HTTP requests. All requests for a session must hit the **same pod**. Without sticky sessions, the server can't correlate requests.

### 2. CORS with Credentials

With sticky session cookies, the browser sends cookies cross-origin. You **cannot** use `setAllowedOrigins("*")` — must use specific origins:

```java
// ❌ Won't work with cookies
.setAllowedOrigins("*")

// ✅ Required for cookie-based sticky sessions
.setAllowedOriginPatterns("https://*.yourdomain.com")
```

### 3. SockJS Info Endpoint

SockJS makes a `GET /ws/info` request before connecting to discover server capabilities. This endpoint must be accessible and return proper CORS headers. Spring handles this automatically when `.withSockJS()` is configured.

### 4. Increased Server Load

| Transport     | Connections per user               | Server impact |
|---------------|------------------------------------|---------------|
| WebSocket     | 1 persistent                       | Low           |
| XHR-streaming | 1 persistent + periodic reconnects | Medium        |
| XHR-polling   | New connection every few seconds   | High          |

With 1000 users on XHR-polling, you get thousands of HTTP requests/second instead of 1000 persistent connections.

### 5. Multiple Replicas — Same Problem

Whether using SockJS or native WebSocket, with multiple pods you still need an **external message broker** (RabbitMQ/Redis) for cross-pod messaging. This is unrelated to SockJS.

---

## Nginx (Non-K8s) Configuration

### With SockJS:

```nginx
upstream websocket_backend {
    ip_hash;  # Sticky sessions by client IP
    server backend1:8080;
    server backend2:8080;
}

server {
    listen 443 ssl;
    server_name ws.yourdomain.com;

    ssl_certificate     /etc/ssl/certs/yourdomain.crt;
    ssl_certificate_key /etc/ssl/private/yourdomain.key;

    location /ws {
        proxy_pass http://websocket_backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
    }
}
```

### Without SockJS:

```nginx
upstream websocket_backend {
    # No ip_hash needed — no sticky sessions required
    server backend1:8080;
    server backend2:8080;
}

server {
    listen 443 ssl;
    server_name ws.yourdomain.com;

    ssl_certificate     /etc/ssl/certs/yourdomain.crt;
    ssl_certificate_key /etc/ssl/private/yourdomain.key;

    location = /ws {
        proxy_pass http://websocket_backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
    }
}
```

---

## AWS ALB Configuration

### With SockJS:

- Enable sticky sessions on the target group (application-based cookie)
- Set idle timeout to 3600s
- Ensure WebSocket protocol is supported (ALB supports it natively)

### Without SockJS:

- Set idle timeout to 3600s
- No sticky sessions needed

---

## Recommendation

| Environment                                 | Recommendation                                  |
|---------------------------------------------|-------------------------------------------------|
| Internal/corporate app (controlled clients) | Remove SockJS — use native WebSocket            |
| Public-facing app (unknown clients)         | Keep SockJS as safety net                       |
| Mobile-only (native apps)                   | Remove SockJS                                   |
| Must support legacy browsers                | Keep SockJS                                     |
| K8s with multiple replicas                  | Remove SockJS (simpler infra) + external broker |

---

## Key Takeaways

1. **SockJS adds infrastructure complexity** — sticky sessions, prefix routing, CORS with credentials
2. **Native WebSocket is simpler** — one persistent connection, no sticky sessions, lower latency
3. **Modern browsers all support WebSocket** — SockJS is only needed for edge cases
4. **Make it configurable** — use `application.yaml` to toggle SockJS per environment
5. **Both options require the same interceptor chain** — auth, tracing, rate limiting all work identically regardless of transport
6. **Cross-pod messaging** is a separate concern — requires external broker regardless of SockJS

