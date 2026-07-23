# WebSocket Monitoring & Metrics

## Overview

Monitoring a WebSocket application requires tracking:
- **Connection health** — how many users are connected, disconnect patterns
- **Message throughput** — inbound/outbound message rates
- **Performance** — message processing latency
- **Security events** — auth failures, rate limit hits
- **Server resources** — thread pool usage, buffer utilization

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Monitoring Stack                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Spring Boot App                                                    │
│  ├── WebsocketMetrics (custom counters/gauges)                      │
│  ├── Micrometer Observations (from tracing interceptor)             │
│  ├── Actuator endpoints (/actuator/prometheus, /actuator/metrics)   │
│  └── Admin endpoint (/admin/ws-stats)                               │
│                          │                                          │
│                          ▼                                          │
│  Prometheus (scrapes /actuator/prometheus)                          │
│                          │                                          │
│                          ▼                                          │
│  Grafana (dashboards & alerts)                                      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 1. Custom WebSocket Metrics

### `WebsocketMetrics.java`

```java
package com.moh.yehia.websocket.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WebsocketMetrics {
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final Counter messagesReceived;
    private final Counter messagesSent;
    private final Counter connectionsRejected;
    private final Counter rateLimitHits;
    private final Counter authFailure;
    private final MeterRegistry meterRegistry;

    public WebsocketMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        Gauge.builder("websocket.connections.active", activeConnections, AtomicInteger::get)
                .description("Number of active WebSocket connections")
                .register(meterRegistry);

        messagesReceived = Counter.builder("websocket.messages.received")
                .description("Total messages received from clients")
                .register(meterRegistry);

        messagesSent = Counter.builder("websocket.messages.sent")
                .description("Total messages sent to clients")
                .register(meterRegistry);

        connectionsRejected = Counter.builder("websocket.connections.rejected")
                .description("Connections rejected (auth failure, session limit)")
                .register(meterRegistry);

        rateLimitHits = Counter.builder("websocket.ratelimit.hits")
                .description("Number of rate limit violations")
                .register(meterRegistry);

        authFailure = Counter.builder("websocket.auth.failure")
                .description("JWT authentication failure")
                .register(meterRegistry);
    }

    public void onConnect() { activeConnections.incrementAndGet(); }
    public void onDisconnect() { activeConnections.decrementAndGet(); }
    public void onMessageReceived() { messagesReceived.increment(); }
    public void onMessageSent() { messagesSent.increment(); }
    public void onConnectionRejected() { connectionsRejected.increment(); }
    public void onRateLimitHits() { rateLimitHits.increment(); }
    public void onAuthFailure() { authFailure.increment(); }

    // Disconnect by close code — tracks disconnect patterns
    public void onDisconnectByCode(int closeCode) {
        Counter.builder("websocket.disconnects")
                .tag("code", String.valueOf(closeCode))
                .description("Disconnects by close status code")
                .register(meterRegistry)
                .increment();
    }
}
```

---

## 2. Metrics Table

| Metric Name                      | Type    | Description                          | Source                   |
|----------------------------------|---------|--------------------------------------|--------------------------|
| `websocket.connections.active`   | Gauge   | Current number of active connections | Event listener           |
| `websocket.messages.received`    | Counter | Total inbound messages               | Rate limiter interceptor |
| `websocket.messages.sent`        | Counter | Total outbound messages              | NotificationService      |
| `websocket.connections.rejected` | Counter | Rejected connections (session limit) | UserSessionTracker       |
| `websocket.ratelimit.hits`       | Counter | Rate limit violations                | Rate limiter interceptor |
| `websocket.auth.failure`         | Counter | JWT authentication failures          | AuthorizationInterceptor |
| `websocket.disconnects{code=X}`  | Counter | Disconnects by close code            | Event listener           |

---

## 3. Where Each Metric Is Called

| Location                                   | Metrics called                               |
|--------------------------------------------|----------------------------------------------|
| `WebSocketEventListener.handleConnect`     | `onConnect()`                                |
| `WebSocketEventListener.handleDisconnect`  | `onDisconnect()`, `onDisconnectByCode(code)` |
| `MessagesPerSessionRateLimiterInterceptor` | `onMessageReceived()`, `onRateLimitHits()`   |
| `NotificationService`                      | `onMessageSent()`                            |
| `UserSessionTracker`                       | `onConnectionRejected()`                     |
| `AuthorizationInterceptor`                 | `onAuthFailure()`                            |

---

## 4. Latency & Performance

**Already covered by the tracing interceptor.** The `Observation` created in `WebsocketTracingChannelInterceptor` automatically registers a `Timer` metric. Check in Prometheus:

```
websocket_inbound_seconds_count
websocket_inbound_seconds_sum
websocket_inbound_seconds_max
```

These give you message processing latency without any additional code.

### Grafana query for average latency:

```promql
rate(websocket_inbound_seconds_sum[5m]) / rate(websocket_inbound_seconds_count[5m])
```

---

## 5. Session Health — Disconnect Patterns

Track disconnect close codes to identify infrastructure issues:

```java
// In WebSocketEventListener:
@EventListener
void handleWebsocketDisconnectListener(SessionDisconnectEvent event) {
    websocketMetrics.onDisconnect();
    websocketMetrics.onDisconnectByCode(event.getCloseStatus().getCode());
    // ...cleanup...
}
```

### Close code interpretation:

| Code | Meaning        | If high count →                                      |
|------|----------------|------------------------------------------------------|
| 1000 | Normal         | Healthy — users disconnecting intentionally          |
| 1001 | Going Away     | Users navigating away, or server deployments         |
| 1002 | Protocol Error | Bug in interceptors or client library                |
| 1006 | Abnormal       | Network issues, LB timeouts, infrastructure problems |

### Alert example:

```promql
# Alert if abnormal disconnects exceed 10% of total in 5 minutes
rate(websocket_disconnects_total{code="1006"}[5m]) 
  / rate(websocket_disconnects_total[5m]) > 0.1
```

---

## 6. Message Throughput

Already covered by `websocket.messages.received` and `websocket.messages.sent` counters.

### Grafana queries:

```promql
# Messages received per second
rate(websocket_messages_received_total[1m])

# Messages sent per second
rate(websocket_messages_sent_total[1m])

# Total throughput (in + out)
rate(websocket_messages_received_total[1m]) + rate(websocket_messages_sent_total[1m])
```

---

## 7. Server Resource Utilization

Available via `WebSocketMessageBrokerStats` at the `/admin/ws-stats` endpoint:

```json
{
  "sessionStats": { "webSocketSessions": 42, "httpStreamingSessions": 0 },
  "totalConnectedUsers": 35,
  "inboundChannelStats": "pool size = 4, active threads = 1, queued tasks = 0",
  "outboundChannelStats": "pool size = 4, active threads = 0, queued tasks = 0"
}
```

### Key indicators:

| Stat                                         | What it tells you                                           |
|----------------------------------------------|-------------------------------------------------------------|
| `active threads` close to `pool size`        | Thread pool saturation — increase pool size                 |
| `queued tasks > 0` consistently              | Messages backing up — increase threads or optimize handlers |
| `webSocketSessions` >> `totalConnectedUsers` | Users with many tabs — consider session limits              |

Spring Boot Actuator also exposes executor metrics if you name your thread pools:
- `executor.active` — active threads
- `executor.queued` — queued tasks
- `executor.pool.size` — current pool size

---

## 8. Admin Stats Endpoint

### `WebsocketStatsController.java`

```java
@RestController
@RequestMapping("/admin")
public class WebsocketStatsController {
    private final WebSocketMessageBrokerStats webSocketMessageBrokerStats;
    private final SimpUserRegistry simpUserRegistry;

    @GetMapping("/ws-stats")
    Map<String, Object> getWebSocketStats() {
        return Map.of(
                "sessionStats", webSocketMessageBrokerStats.getWebSocketSessionStats(),
                "totalConnectedUsers", simpUserRegistry.getUserCount(),
                "connectedUsers", simpUserRegistry.getUsers().stream()
                        .map(u -> Map.of(
                                "username", u.getName(),
                                "activeSessions", u.getSessions().size()
                        )).toList(),
                "stompSubProtocolStats", webSocketMessageBrokerStats.getStompSubProtocolStats(),
                "stompBrokerRelayStats", webSocketMessageBrokerStats.getStompBrokerRelayStats(),
                "inboundChannelStats", webSocketMessageBrokerStats.getClientInboundExecutorStatsInfo(),
                "outboundChannelStats", webSocketMessageBrokerStats.getClientOutboundExecutorStatsInfo()
        );
    }
}
```

Secured via:
```java
.requestMatchers("/admin/**").hasRole("ADMIN")
```

---

## 9. Actuator Configuration

### `application.yaml`

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always
      show-components: always
  endpoints:
    web:
      exposure:
        include: health, metrics, info, prometheus
  tracing:
    sampling:
      probability: 1.0
    export:
      enabled: true
```

### Prometheus scrape config:

```yaml
scrape_configs:
  - job_name: 'websocket-app'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s
    static_configs:
      - targets: ['localhost:8080']
```

---

## 10. Grafana Dashboard — Recommended Panels

| Panel                    | Query                                                                                 | Type                        |
|--------------------------|---------------------------------------------------------------------------------------|-----------------------------|
| Active Connections       | `websocket_connections_active`                                                        | Gauge                       |
| Message Throughput (in)  | `rate(websocket_messages_received_total[1m])`                                         | Graph                       |
| Message Throughput (out) | `rate(websocket_messages_sent_total[1m])`                                             | Graph                       |
| Rate Limit Violations    | `rate(websocket_ratelimit_hits_total[5m])`                                            | Graph                       |
| Connections Rejected     | `increase(websocket_connections_rejected_total[5m])`                                  | Stat                        |
| Disconnects by Code      | `rate(websocket_disconnects_total[5m])`                                               | Stacked bar (by code label) |
| Avg Message Latency      | `rate(websocket_inbound_seconds_sum[5m]) / rate(websocket_inbound_seconds_count[5m])` | Graph                       |
| Auth Failures            | `increase(websocket_connections_rejected_total[5m])`                                  | Stat                        |

---

## 11. Alerting Rules (Prometheus)

```yaml
groups:
  - name: websocket_alerts
    rules:
      - alert: HighAbnormalDisconnects
        expr: rate(websocket_disconnects_total{code="1006"}[5m]) > 0.5
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "High rate of abnormal WebSocket disconnects"

      - alert: RateLimitBreach
        expr: rate(websocket_ratelimit_hits_total[5m]) > 1
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Users hitting rate limits frequently"

      - alert: NoActiveConnections
        expr: websocket_connections_active == 0
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "No active WebSocket connections for 5 minutes"

      - alert: HighConnectionRejections
        expr: rate(websocket_connections_rejected_total[5m]) > 0.5
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "High rate of connection rejections"
```

---

## 12. Logging for Production

### Profile-specific logging:

```yaml
# application.yaml (base — production)
logging:
  pattern:
    correlation: "[${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
  level:
    org.springframework.web.socket: warn

# application-dev.yaml (development only)
logging:
  level:
    org.springframework.web.socket.messaging.StompSubProtocolHandler: trace
    org.springframework.web.socket.sockjs.transport: debug
```

### Structured JSON logging (production with log aggregation):

```yaml
# application-prod.yaml
logging:
  structured:
    format: ecs  # Elastic Common Schema — works with ELK, Loki, CloudWatch
```

---

## Key Takeaways

1. **6 custom metrics** cover the essential WebSocket monitoring needs
2. **Disconnect-by-code counter** helps identify infrastructure vs. client issues
3. **Latency is auto-tracked** by the tracing `Observation` — no extra code needed
4. **Message throughput** is derived from counters using `rate()` in Grafana
5. **Server resource utilization** is available via `/admin/ws-stats` and Actuator executor metrics
6. **Alerting** on abnormal disconnects and rate limit breaches catches problems early
7. **Debug logging** should be profile-specific — never in production

