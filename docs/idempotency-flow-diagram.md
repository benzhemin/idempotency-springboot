# Idempotency Flow Diagrams

## 1. Cache Miss (First Request)

```mermaid
sequenceDiagram
    actor Client
    participant Controller as Controller<br/>@Idempotent
    participant Aspect as IdempotencyAspect<br/>(@Around AOP)
    participant Store as IdempotencyStore<br/>(interface)
    participant Redis as Redis
    participant Service as Business Logic

    Client->>Controller: POST /api/orders<br/>Header: Idempotency-Key: abc-123<br/>Body: {"amount": 100}

    Note over Controller,Aspect: Spring resolves @RequestBody BEFORE AOP executes<br/>Body is already deserialized into Java object

    Controller->>Aspect: AOP intercepts (before method runs)

    Aspect->>Aspect: Extract header "abc-123"
    Aspect->>Aspect: Build key: idempotency:order-create:abc-123
    Aspect->>Aspect: includeBody=true → hash joinPoint.getArgs()<br/>bodyHash = SHA-256(objectMapper.writeValueAsString(body))

    Aspect->>Store: get("idempotency:order-create:abc-123")
    Store->>Redis: GET idempotency:order-create:abc-123
    Redis-->>Store: nil
    Store-->>Aspect: Optional.empty()

    Note over Aspect: CACHE MISS → proceed to controller

    Aspect->>Controller: joinPoint.proceed()
    Controller->>Service: createOrder(request)
    Service-->>Controller: Order {id: 42}
    Controller-->>Aspect: ResponseEntity<201, {id: 42}>

    Aspect->>Aspect: Status 201 is 2xx → cache it
    Aspect->>Aspect: Serialize body: objectMapper.writeValueAsString({id: 42})

    Aspect->>Store: put(key, CachedResponse{201, body, bodyHash}, 24h)
    Store->>Redis: SET idempotency:order-create:abc-123<br/>{"statusCode":201,"body":"{\"id\":42}","bodyHash":"e3b0c..."}<br/>EX 86400
    Redis-->>Store: OK

    Aspect-->>Client: 201 Created<br/>{"id": 42}
```

## 2. Cache Hit (Duplicate Request — Same Body)

```mermaid
sequenceDiagram
    actor Client
    participant Controller as Controller<br/>@Idempotent
    participant Aspect as IdempotencyAspect<br/>(@Around AOP)
    participant Store as IdempotencyStore
    participant Redis as Redis
    participant Service as Business Logic

    Client->>Controller: POST /api/orders<br/>Header: Idempotency-Key: abc-123<br/>Body: {"amount": 100}

    Controller->>Aspect: AOP intercepts

    Aspect->>Aspect: Extract header "abc-123"
    Aspect->>Aspect: Build key: idempotency:order-create:abc-123
    Aspect->>Aspect: Hash body from joinPoint.getArgs()

    Aspect->>Store: get("idempotency:order-create:abc-123")
    Store->>Redis: GET idempotency:order-create:abc-123
    Redis-->>Store: {"statusCode":201,"body":"{\"id\":42}","bodyHash":"e3b0c..."}
    Store-->>Aspect: Optional.of(CachedResponse)

    Note over Aspect: CACHE HIT

    Aspect->>Aspect: includeBody=true → compare body hashes
    Aspect->>Aspect: currentHash == cachedHash ✓ MATCH

    Note over Aspect,Service: Controller method NEVER executes<br/>Business logic is NOT called<br/>No duplicate order created

    Aspect-->>Client: 201 Created<br/>{"id": 42}<br/>(replayed from cache)
```

## 3. Body Mismatch (Same Key, Different Body → 422)

```mermaid
sequenceDiagram
    actor Client
    participant Controller as Controller<br/>@Idempotent
    participant Aspect as IdempotencyAspect<br/>(@Around AOP)
    participant Store as IdempotencyStore
    participant Redis as Redis
    participant Service as Business Logic

    Client->>Controller: POST /api/orders<br/>Header: Idempotency-Key: abc-123<br/>Body: {"amount": 999}
    Note right of Client: Same key "abc-123"<br/>but DIFFERENT body

    Controller->>Aspect: AOP intercepts

    Aspect->>Aspect: Extract header "abc-123"
    Aspect->>Aspect: Build key: idempotency:order-create:abc-123
    Aspect->>Aspect: Hash body: SHA-256({"amount":999}) = "f4a1d..."

    Aspect->>Store: get("idempotency:order-create:abc-123")
    Store->>Redis: GET idempotency:order-create:abc-123
    Redis-->>Store: {"statusCode":201,"body":"...","bodyHash":"e3b0c..."}
    Store-->>Aspect: Optional.of(CachedResponse)

    Note over Aspect: CACHE HIT

    Aspect->>Aspect: includeBody=true → compare body hashes
    Aspect->>Aspect: "f4a1d..." != "e3b0c..." ✗ MISMATCH

    Note over Aspect,Service: Controller method NEVER executes<br/>Client is reusing an idempotency key<br/>with a different payload — this is a client error

    Aspect-->>Client: 422 Unprocessable Entity<br/>"Idempotency key 'abc-123' was already<br/>used with a different request body"
```

## 4. Missing Header — Mandatory vs Optional

```mermaid
sequenceDiagram
    actor Client
    participant Controller as Controller
    participant Aspect as IdempotencyAspect
    participant Service as Business Logic

    rect rgb(255, 235, 235)
        Note over Client,Service: Scenario A: mandatory=true (default)
        Client->>Controller: POST /api/orders<br/>⚠️ No Idempotency-Key header
        Controller->>Aspect: AOP intercepts
        Aspect->>Aspect: Header is null + mandatory=true
        Aspect-->>Client: 400 Bad Request<br/>"Missing required idempotency header: Idempotency-Key"
        Note over Service: Controller and Service NEVER execute
    end

    rect rgb(235, 255, 235)
        Note over Client,Service: Scenario B: mandatory=false
        Client->>Controller: POST /api/notifications<br/>⚠️ No Idempotency-Key header
        Controller->>Aspect: AOP intercepts
        Aspect->>Aspect: Header is null + mandatory=false
        Note over Aspect: Skip idempotency entirely
        Aspect->>Controller: joinPoint.proceed()
        Controller->>Service: sendNotification(request)
        Service-->>Controller: Notification {id: 7}
        Controller-->>Aspect: ResponseEntity<200>
        Note over Aspect: No caching (no key to cache under)
        Aspect-->>Client: 200 OK<br/>{"id": 7}
    end
```

## 5. Redis Unavailable (Fail-Open)

```mermaid
sequenceDiagram
    actor Client
    participant Controller as Controller
    participant Aspect as IdempotencyAspect
    participant Store as IdempotencyStore
    participant Redis as Redis<br/>⛔ DOWN
    participant Service as Business Logic

    Client->>Controller: POST /api/orders<br/>Header: Idempotency-Key: abc-123

    Controller->>Aspect: AOP intercepts
    Aspect->>Aspect: Extract header, build key

    Aspect->>Store: get("idempotency:order-create:abc-123")
    Store->>Redis: GET idempotency:order-create:abc-123
    Redis-->>Store: ⛔ RedisConnectionException

    Note over Aspect: FAIL-OPEN: log warning, proceed without idempotency<br/>Rationale: Redis is a cache, not a hard dependency.<br/>Better to process a possible duplicate than reject all requests.

    Aspect->>Aspect: log.warn("Redis unavailable...")
    Aspect->>Controller: joinPoint.proceed()
    Controller->>Service: createOrder(request)
    Service-->>Controller: Order {id: 43}
    Controller-->>Aspect: ResponseEntity<201>

    Note over Aspect: Cannot cache (Redis down) — skip silently

    Aspect-->>Client: 201 Created<br/>{"id": 43}
```

## 6. Complete Component Architecture

```mermaid
flowchart TB
    Client["Client<br/><i>sends Idempotency-Key header</i>"]

    subgraph Spring["Spring Boot Application"]
        direction TB

        subgraph MVC["Spring MVC"]
            Dispatcher["DispatcherServlet<br/><i>1. resolves @RequestBody</i><br/><i>2. calls controller via AOP proxy</i>"]
        end

        subgraph AOP["AOP Layer"]
            Aspect["IdempotencyAspect<br/><i>@Around @annotation(idempotent)</i>"]
        end

        subgraph Controllers["Controller Layer"]
            C1["OrderController<br/><code>@Idempotent(keyPrefix='orders',<br/>ttl=24, timeUnit=HOURS,<br/>includeBody=true)</code>"]
            C2["PaymentController<br/><code>@Idempotent(keyPrefix='payments',<br/>mandatory=true)</code>"]
            C3["NotificationController<br/><code>@Idempotent(keyPrefix='notify',<br/>mandatory=false)</code>"]
        end

        subgraph Store["Store Layer"]
            Interface["IdempotencyStore<br/><i>interface</i>"]
            RedisImpl["RedisIdempotencyStore<br/><i>StringRedisTemplate + Jackson</i>"]
        end

        Config["IdempotencyConfig<br/><i>@Bean idempotencyStore()</i>"]
    end

    Redis[("Redis<br/><i>Key: idempotency:{prefix}:{key}</i><br/><i>Value: JSON CachedResponse</i><br/><i>TTL: configurable per endpoint</i>")]

    Client -->|"HTTP POST + header"| Dispatcher
    Dispatcher -->|"resolved args"| Aspect
    Aspect -->|"proceed()"| C1 & C2 & C3
    Aspect -->|"get/put"| Interface
    Interface -.->|"implements"| RedisImpl
    RedisImpl -->|"GET/SET EX"| Redis
    Config -.->|"wires"| Interface

    style Aspect fill:#fff3e0,stroke:#e65100
    style Interface fill:#e3f2fd,stroke:#1565c0
    style Redis fill:#fce4ec,stroke:#c62828
```

## 7. Redis Key Structure

```mermaid
flowchart LR
    subgraph Key Format
        direction LR
        P["idempotency"] --- Sep1[":"] --- Prefix["keyPrefix"] --- Sep2[":"] --- Header["headerValue"]
    end

    subgraph Examples
        E1["idempotency:order-create:abc-123"]
        E2["idempotency:payments:pay-xyz-789"]
        E3["idempotency::no-prefix-key"]
    end

    subgraph Value["Value (JSON)"]
        V1["{<br/>  statusCode: 201,<br/>  body: '{\"id\":42}',<br/>  bodyHash: 'e3b0c...' or null<br/>}"]
    end

    subgraph TTL
        T1["Configurable per @Idempotent<br/>Default: 1 HOUR<br/>Example: @Idempotent(ttl=24, timeUnit=HOURS)"]
    end

    Key_Format --> Examples
    Examples --> Value
    Value --> TTL

    style P fill:#e8f5e9
    style Prefix fill:#fff3e0
    style Header fill:#e3f2fd
```
