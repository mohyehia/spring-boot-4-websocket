# External Message Broker Configuration (RabbitMQ)

## Overview

The application supports two broker modes, controlled by `websocket.broker.type`:

| Mode       | Value    | Use Case                                      |
|------------|----------|-----------------------------------------------|
| **Simple** | `SIMPLE` | Single instance, development, testing         |
| **Relay**  | `RELAY`  | Multi-instance production, horizontal scaling |

The simple broker runs in-memory inside the JVM. The relay delegates all subscription and message routing to an external RabbitMQ instance via the STOMP protocol.

---

## Configuration

### application.yaml

```yaml
websocket:
  broker:
    type: RELAY              # SIMPLE or RELAY
    relay-host: localhost
    relay-port: 61613        # RabbitMQ STOMP port
    client-login: user
    client-passcode: secret
    system-login: user
    system-passcode: secret
```

### WebSocketConfig (conditional broker setup)

```java
if (brokerType.equalsIgnoreCase(BrokerType.RELAY.name())) {
    messageBrokerRegistry.enableStompBrokerRelay("/topic", "/queue")
            .setRelayHost(relayHost)
            .setRelayPort(relayPort)
            .setClientLogin(clientLogin)
            .setClientPasscode(clientPasscode)
            .setSystemLogin(systemLogin)
            .setSystemPasscode(systemPasscode)
            .setVirtualHost("/")
            .setSystemHeartbeatSendInterval(10_000)
            .setSystemHeartbeatReceiveInterval(10_000)
            .setTaskScheduler(heartbeatScheduler);
} else {
    messageBrokerRegistry.enableSimpleBroker("/topic", "/queue")
            .setHeartbeatValue(new long[]{10_000, 10_000})
            .setTaskScheduler(heartbeatScheduler);
}
```

### Required Dependencies

```xml
<!-- AMQP support -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>

<!-- Reactor Netty TCP client (used by StompBrokerRelay) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-reactor-netty</artifactId>
</dependency>
```

---

## RabbitMQ Setup

### docker-compose.yaml

```yaml
services:
  rabbitmq:
    image: rabbitmq:management-alpine
    container_name: rabbitmq
    environment:
      RABBITMQ_DEFAULT_PASS: secret
      RABBITMQ_DEFAULT_USER: user
    ports:
      - "5672:5672"      # AMQP
      - "15672:15672"    # Management UI
      - "61613:61613"    # STOMP (TCP)
      - "15674:15674"    # Web STOMP (WebSockets)
    volumes:
      - ./volumes/rabbitmq/rabbitmq-enabled-plugins:/etc/rabbitmq/enabled_plugins
```

### enabled_plugins file

```erlang
[rabbitmq_management,rabbitmq_stomp,rabbitmq_web_stomp].
```

The STOMP plugin (`rabbitmq_stomp`) is **required** — it enables RabbitMQ to accept STOMP protocol connections on port 61613. Without it, connections are refused.

### Common Errors

| Error                                      | Cause                    | Fix                                                  |
|--------------------------------------------|--------------------------|------------------------------------------------------|
| `TCP connection failure: Connection reset` | STOMP plugin not enabled | Ensure `rabbitmq_stomp` is in `enabled_plugins`      |
| `Virtual host 'localhost' access denied`   | Default vhost mismatch   | Add `.setVirtualHost("/")` to relay config           |
| `Bad CONNECT`                              | Wrong credentials        | Match `client-login`/`system-login` to RabbitMQ user |

---

## Simple Broker vs Relay: Comparison

| Aspect                 | Simple Broker            | STOMP Broker Relay (RabbitMQ)                |
|------------------------|--------------------------|----------------------------------------------|
| State                  | In-memory, single JVM    | External, shared across instances            |
| Scalability            | Single server only       | Multiple app instances share subscriptions   |
| Persistence            | Messages lost on restart | Can persist messages (durable queues)        |
| Features               | Basic pub/sub            | Full AMQP features (DLQ, TTL, routing)       |
| `/user` destinations   | Resolved locally         | Resolved via RabbitMQ (works cross-instance) |
| Heartbeat              | App ↔ Client             | App ↔ RabbitMQ ↔ Client (two hops)           |
| Failure impact         | App crash = all lost     | Broker down = delivery fails, app still runs |
| Operational complexity | Zero                     | Must manage RabbitMQ (or cluster)            |

---

## Production Considerations

### 1. Broker Unavailability / Connection Loss

When RabbitMQ goes down while the app is running:

- Spring's `StompBrokerRelayMessageHandler` logs TCP connection failures and attempts **automatic reconnection** with exponential backoff
- During downtime: all `subscribe` and `send` operations to broker destinations (`/topic/*`, `/queue/*`) **fail silently** — messages are lost
- `/app/*` destinations (handled by `@MessageMapping`) still work locally, but if the handler calls `convertAndSendToUser()`, that delivery fails

**Mitigation options:**
- Expose a health indicator that marks the app unhealthy when broker disconnects
- Accept message loss for ephemeral real-time notifications (they're transient by nature)
- For must-deliver messages, use a hybrid approach (see Message Durability below)

---

### 2. Message Durability

By default, STOMP subscriptions create **auto-delete, non-durable queues** in RabbitMQ. If a user disconnects and reconnects, they miss messages sent during the gap.

**Hybrid approach: STOMP (real-time) + AMQP (persistent)**

```
                ┌──────────────────────────────────────┐
                │            RabbitMQ                   │
                │                                      │
STOMP relay ──► │  transient queues (real-time, live)   │ ◄── WebSocket users
                │                                      │
AMQP producer ► │  durable queue (persistent, replay)  │ ◄── AMQP consumer (app)
                └──────────────────────────────────────┘
```

```java
@Service
public class NotificationService {

    private final SimpMessagingTemplate messagingTemplate;
    private final RabbitTemplate rabbitTemplate;

    // Real-time delivery (best-effort, via STOMP relay)
    public void sendRealTime(String username, Object payload) {
        messagingTemplate.convertAndSendToUser(username, "/queue/notifications", payload);
    }

    // Guaranteed delivery (persisted in RabbitMQ, consumed later)
    public void sendPersistent(String username, Object payload) {
        rabbitTemplate.convertAndSend(
                "notifications.exchange",
                "user." + username,
                payload,
                message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                }
        );
    }

    // Both: real-time + persist for offline users
    public void sendWithGuarantee(String username, Object payload) {
        sendPersistent(username, payload);  // always persist
        sendRealTime(username, payload);    // best-effort real-time
    }
}
```

**RabbitMQ queue/exchange setup:**

```java
@Configuration
public class RabbitMqConfig {

    @Bean
    public DirectExchange notificationsExchange() {
        return new DirectExchange("notifications.exchange", true, false); // durable
    }

    @Bean
    public Queue userNotificationsQueue() {
        return QueueBuilder.durable("notifications.persistent")
                .withArgument("x-dead-letter-exchange", "notifications.dlx")
                .build();
    }

    @Bean
    public Binding binding(Queue userNotificationsQueue, DirectExchange notificationsExchange) {
        return BindingBuilder.bind(userNotificationsQueue)
                .to(notificationsExchange)
                .with("user.*");
    }
}
```

**Client-side:** When a user reconnects, call a REST endpoint to fetch missed messages from a database (populated by the AMQP consumer).

**When to use:** Only for critical notifications (payment confirmations, security alerts). For live dashboards or chat, real-time-only is sufficient.

---

### 3. Dead Letter Queues (DLQ)

Messages that can't be delivered are silently dropped by default. DLQs capture them for debugging and auditing.

**Setup:**

```java
@Configuration
public class DlqConfig {

    @Bean
    public FanoutExchange deadLetterExchange() {
        return new FanoutExchange("notifications.dlx", true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable("notifications.dlq")
                .withArgument("x-message-ttl", 604_800_000) // 7 days TTL
                .build();
    }

    @Bean
    public Binding dlqBinding(Queue deadLetterQueue, FanoutExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange);
    }
}
```

**Messages end up in DLQ when:**
- Consumer rejects them (nack without requeue)
- Message TTL expires on the source queue
- Queue max length exceeded

**Monitoring:** Set up alerts on DLQ depth via Prometheus + RabbitMQ exporter, or check the Management UI at `http://localhost:15672`.

---

### 4. Cluster Mode (High Availability)

A single RabbitMQ node is a single point of failure. For HA, run a cluster.

**docker-compose for 3-node cluster:**

```yaml
services:
  rabbitmq-1:
    image: rabbitmq:management-alpine
    hostname: rabbitmq-1
    environment:
      RABBITMQ_ERLANG_COOKIE: "secret-cookie"
      RABBITMQ_DEFAULT_USER: user
      RABBITMQ_DEFAULT_PASS: secret
    volumes:
      - ./volumes/rabbitmq/rabbitmq-enabled-plugins:/etc/rabbitmq/enabled_plugins
    ports:
      - "5672:5672"
      - "15672:15672"
      - "61613:61613"

  rabbitmq-2:
    image: rabbitmq:management-alpine
    hostname: rabbitmq-2
    environment:
      RABBITMQ_ERLANG_COOKIE: "secret-cookie"
      RABBITMQ_DEFAULT_USER: user
      RABBITMQ_DEFAULT_PASS: secret
      RABBITMQ_CLUSTER_NODES: "rabbit@rabbitmq-1"
    volumes:
      - ./volumes/rabbitmq/rabbitmq-enabled-plugins:/etc/rabbitmq/enabled_plugins
    depends_on:
      - rabbitmq-1

  rabbitmq-3:
    image: rabbitmq:management-alpine
    hostname: rabbitmq-3
    environment:
      RABBITMQ_ERLANG_COOKIE: "secret-cookie"
      RABBITMQ_DEFAULT_USER: user
      RABBITMQ_DEFAULT_PASS: secret
      RABBITMQ_CLUSTER_NODES: "rabbit@rabbitmq-1"
    volumes:
      - ./volumes/rabbitmq/rabbitmq-enabled-plugins:/etc/rabbitmq/enabled_plugins
    depends_on:
      - rabbitmq-1
```

**Quorum queues** (replicated across cluster nodes):

```java
@Bean
public Queue notificationsQueue() {
    return QueueBuilder.durable("notifications.persistent")
            .quorum()  // replicated across cluster nodes
            .withArgument("x-dead-letter-exchange", "notifications.dlx")
            .build();
}
```

**App connection to cluster:**

Spring's STOMP relay only supports a single host. For failover, use a **load balancer** in front of the cluster:

```yaml
websocket:
  broker:
    relay-host: rabbitmq-lb   # HAProxy/Nginx routing to healthy node
    relay-port: 61613
```

**When to use:** Production with >99.9% uptime SLA. For development/staging, single node is fine.

---

### 5. Broker-Level Access Control

All WebSocket users share the same `client-login`/`client-passcode` credentials. This means any connected STOMP client could theoretically subscribe to any queue the STOMP plugin exposes.

**Current mitigation:** The `AuthorizationChannelInterceptor` enforces destination-level access at the application layer — this is sufficient for most use cases.

**Additional hardening (optional):**
- Configure RabbitMQ vhost/topic permissions per user
- Use per-user credentials via a custom `StompBrokerRelayMessageHandler` that maps the authenticated principal to RabbitMQ credentials

---

### 6. Message Ordering

`setPreservePublishOrder(true)` ensures messages sent to the same session are delivered in order. With RabbitMQ, ordering is guaranteed per-queue. Since each user subscription maps to its own queue, per-user message ordering is maintained.

---

### 7. Multi-Instance User Destination Resolution

With multiple app instances behind a load balancer and RabbitMQ relay:

1. Alice connects to **Instance A** → subscribes to `/user/queue/notifications` → Spring creates a RabbitMQ queue binding for `/queue/notifications-useralice<sessionId>`
2. **Instance B** calls `convertAndSendToUser("alice", "/queue/notifications", msg)` → Spring sends to `/queue/notifications-useralice<sessionId>` → **RabbitMQ delivers it to Alice's subscription regardless of which server sent it**

This works automatically with the relay — no additional configuration needed. The subscription state lives in RabbitMQ, not in-memory.

> **Note:** With the simple broker, multi-instance user destination resolution is impossible — subscriptions are in-memory and cannot be shared across JVMs.

---

### 8. Testing with Testcontainers

Tests default to `websocket.broker.type=simple` to avoid needing RabbitMQ. To also test the relay path, use Testcontainers:

**Dependency:**

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>rabbitmq</artifactId>
    <scope>test</scope>
</dependency>
```

**Test class:**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RabbitMqRelayIntegrationTest {

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:management-alpine")
            .withPluginsEnabled("rabbitmq_stomp", "rabbitmq_web_stomp")
            .withExposedPorts(5672, 61613, 15672);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("websocket.broker.type", () -> "RELAY");
        registry.add("websocket.broker.relay-host", rabbitmq::getHost);
        registry.add("websocket.broker.relay-port", () -> rabbitmq.getMappedPort(61613));
        registry.add("websocket.broker.client-login", () -> "guest");
        registry.add("websocket.broker.client-passcode", () -> "guest");
        registry.add("websocket.broker.system-login", () -> "guest");
        registry.add("websocket.broker.system-passcode", () -> "guest");
        registry.add("websocket.allows-origins", () -> "*");
    }

    @LocalServerPort
    private int port;

    @Test
    void shouldConnectAndReceiveViaRelay() throws Exception {
        var stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new StringMessageConverter());

        String jwt = TestJwtHelper.generateToken("relayuser", List.of("USER"));
        var headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + jwt);

        var session = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                headers,
                new StompSessionHandlerAdapter() {}
        ).get(10, TimeUnit.SECONDS);

        BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        session.subscribe("/user/queue/notifications", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) { return String.class; }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add((String) payload);
            }
        });

        Thread.sleep(500); // wait for subscription to propagate through RabbitMQ

        session.send("/app/notification", "{\"content\":\"hello from relay\"}");

        String response = messages.poll(15, TimeUnit.SECONDS);
        assertThat(response).isNotNull().contains("hello from relay");

        session.disconnect();
    }
}
```

**Key points:**
- `@Container` starts a RabbitMQ instance with STOMP plugin before tests, stops after
- `@DynamicPropertySource` wires the random mapped port into Spring config
- Tests are slower (~5-10s for container startup) — tag with `@Tag("integration")` to separate from fast unit tests
- Verifies the entire relay path: app → RabbitMQ STOMP → back to client

---

## Summary: When to Implement Each Enhancement

| Enhancement               | Implement When...                                      |
|---------------------------|--------------------------------------------------------|
| **Relay mode**            | You need horizontal scaling (multiple app instances)   |
| **Message durability**    | You have must-deliver notifications (payments, alerts) |
| **Dead letter queues**    | Going to production — essential for debugging          |
| **Cluster mode**          | Production HA requirement, >99.9% uptime SLA           |
| **Testcontainers**        | You want CI confidence that the relay path works       |
| **Broker access control** | High-security environments with untrusted clients      |

