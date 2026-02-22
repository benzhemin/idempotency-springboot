# Idempotency Library Design — Spring Boot + Redis

## Overview

Annotation-based idempotency mechanism for Spring Boot REST APIs using Redis as the cache store. Applied via a `@Idempotent` annotation on controller methods, backed by a Spring AOP `@Around` aspect.

## Annotation

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    String headerName() default "Idempotency-Key";
    String keyPrefix() default "";
    long ttl() default 1;
    TimeUnit timeUnit() default TimeUnit.HOURS;
    boolean mandatory() default true;
    boolean includeBody() default false;
}
```

### Attributes

| Attribute | Default | Purpose |
|-----------|---------|---------|
| `headerName` | `"Idempotency-Key"` | HTTP header to extract the idempotency key from |
| `keyPrefix` | `""` | Namespace isolation to prevent key collisions between endpoints |
| `ttl` | `1` | Time-to-live for cached responses |
| `timeUnit` | `HOURS` | Unit for TTL |
| `mandatory` | `true` | If `true`, missing header returns 400. If `false`, skips idempotency. |
| `includeBody` | `false` | If `true`, SHA-256 of request body is included in the key. Detects payload mismatch (422). |

## Architecture

```
Controller (@Idempotent)
        │
        ▼
IdempotencyAspect (@Around)
  1. Extract header
  2. Compute Redis key: idempotency:{prefix}:{headerVal}[:bodyHash]
  3. Check Redis via IdempotencyStore
     ├─ HIT + body match   → return CachedResponse
     ├─ HIT + body mismatch → 422
     └─ MISS → proceed to controller, cache 2xx result
        │
        ▼
IdempotencyStore (interface)
  └─ RedisIdempotencyStore (RedisTemplate)
```

## Cached Response Model

```java
public class CachedResponse {
    int statusCode;
    String body;
    String bodyHash;  // only populated when includeBody=true
}
```

## Error Handling

- **Missing header + mandatory=true** → 400 Bad Request
- **Missing header + mandatory=false** → skip idempotency, proceed normally
- **Same key, different body hash** → 422 Unprocessable Entity
- **Controller exception** → do NOT cache, let exception propagate
- **Redis unavailable** → fail-open, log warning, proceed without idempotency
- **Only 2xx responses are cached**

## Package Structure

```
com.example.idempotency/
├── annotation/
│   └── Idempotent.java
├── aspect/
│   └── IdempotencyAspect.java
├── model/
│   └── CachedResponse.java
├── store/
│   ├── IdempotencyStore.java
│   └── RedisIdempotencyStore.java
├── config/
│   └── IdempotencyConfig.java
└── exception/
    ├── IdempotencyKeyMissingException.java
    └── IdempotencyBodyMismatchException.java
```

## Dependencies

```groovy
implementation 'org.springframework.boot:spring-boot-starter-web'
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
implementation 'org.springframework.boot:spring-boot-starter-aop'
implementation 'com.fasterxml.jackson.core:jackson-databind'
```

## Usage Example

```java
@PostMapping
@Idempotent(keyPrefix = "order-create", ttl = 24, timeUnit = TimeUnit.HOURS, includeBody = true)
public ResponseEntity<Order> createOrder(@RequestBody CreateOrderRequest request) {
    // business logic
}
```
