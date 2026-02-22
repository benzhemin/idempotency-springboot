# Idempotency Library Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build an annotation-based idempotency library for Spring Boot REST APIs using Redis, so endpoints can be protected from duplicate requests by adding `@Idempotent`.

**Architecture:** AOP `@Around` aspect intercepts `@Idempotent`-annotated controller methods, checks Redis for a cached response keyed by the `Idempotency-Key` header, and either returns the cached result or proceeds with execution and caches the result. An `IdempotencyStore` interface abstracts Redis access.

**Tech Stack:** Spring Boot 3.x, Gradle, Spring Data Redis (Lettuce), Spring AOP, Jackson, JUnit 5, Mockito, Testcontainers (Redis)

---

### Task 1: Scaffold Gradle Project

**Files:**
- Create: `build.gradle`
- Create: `settings.gradle`
- Create: `src/main/java/com/example/idempotency/IdempotencyApplication.java`
- Create: `src/main/resources/application.yml`

**Step 1: Create `settings.gradle`**

```groovy
rootProject.name = 'idempotency-library'
```

**Step 2: Create `build.gradle`**

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.4.1'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.example'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-aop'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.testcontainers:junit-jupiter'
    testImplementation 'com.redis:testcontainers-redis:2.2.2'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

**Step 3: Create minimal Spring Boot application class**

```java
package com.example.idempotency;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IdempotencyApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdempotencyApplication.class, args);
    }
}
```

**Step 4: Create `application.yml`**

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

**Step 5: Verify project compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add build.gradle settings.gradle src/
git commit -m "feat: scaffold Spring Boot Gradle project with Redis and AOP dependencies"
```

---

### Task 2: Create CachedResponse Model and Custom Exceptions

**Files:**
- Create: `src/main/java/com/example/idempotency/model/CachedResponse.java`
- Create: `src/main/java/com/example/idempotency/exception/IdempotencyKeyMissingException.java`
- Create: `src/main/java/com/example/idempotency/exception/IdempotencyBodyMismatchException.java`
- Create: `src/test/java/com/example/idempotency/model/CachedResponseTest.java`

**Step 1: Write the failing test for CachedResponse serialization**

```java
package com.example.idempotency.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CachedResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeAndDeserialize() throws Exception {
        CachedResponse original = new CachedResponse(201, "{\"id\":1}", "abc123");

        String json = objectMapper.writeValueAsString(original);
        CachedResponse deserialized = objectMapper.readValue(json, CachedResponse.class);

        assertThat(deserialized.getStatusCode()).isEqualTo(201);
        assertThat(deserialized.getBody()).isEqualTo("{\"id\":1}");
        assertThat(deserialized.getBodyHash()).isEqualTo("abc123");
    }

    @Test
    void shouldHandleNullBodyHash() throws Exception {
        CachedResponse original = new CachedResponse(200, "{\"ok\":true}", null);

        String json = objectMapper.writeValueAsString(original);
        CachedResponse deserialized = objectMapper.readValue(json, CachedResponse.class);

        assertThat(deserialized.getBodyHash()).isNull();
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.example.idempotency.model.CachedResponseTest"`
Expected: FAIL — class CachedResponse does not exist

**Step 3: Implement CachedResponse**

```java
package com.example.idempotency.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class CachedResponse {

    private final int statusCode;
    private final String body;
    private final String bodyHash;

    @JsonCreator
    public CachedResponse(
            @JsonProperty("statusCode") int statusCode,
            @JsonProperty("body") String body,
            @JsonProperty("bodyHash") String bodyHash) {
        this.statusCode = statusCode;
        this.body = body;
        this.bodyHash = bodyHash;
    }

    public int getStatusCode() { return statusCode; }
    public String getBody() { return body; }
    public String getBodyHash() { return bodyHash; }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.example.idempotency.model.CachedResponseTest"`
Expected: PASS

**Step 5: Create IdempotencyKeyMissingException**

```java
package com.example.idempotency.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class IdempotencyKeyMissingException extends RuntimeException {
    public IdempotencyKeyMissingException(String headerName) {
        super("Missing required idempotency header: " + headerName);
    }
}
```

**Step 6: Create IdempotencyBodyMismatchException**

```java
package com.example.idempotency.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class IdempotencyBodyMismatchException extends RuntimeException {
    public IdempotencyBodyMismatchException(String idempotencyKey) {
        super("Idempotency key '" + idempotencyKey + "' was already used with a different request body");
    }
}
```

**Step 7: Verify compilation**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL

**Step 8: Commit**

```bash
git add src/
git commit -m "feat: add CachedResponse model and idempotency exceptions"
```

---

### Task 3: Create @Idempotent Annotation

**Files:**
- Create: `src/main/java/com/example/idempotency/annotation/Idempotent.java`

**Step 1: Create the annotation**

```java
package com.example.idempotency.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

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

**Step 2: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/java/com/example/idempotency/annotation/
git commit -m "feat: add @Idempotent annotation with configurable attributes"
```

---

### Task 4: Create IdempotencyStore Interface and Redis Implementation

**Files:**
- Create: `src/main/java/com/example/idempotency/store/IdempotencyStore.java`
- Create: `src/main/java/com/example/idempotency/store/RedisIdempotencyStore.java`
- Create: `src/test/java/com/example/idempotency/store/RedisIdempotencyStoreTest.java`

**Step 1: Write the failing integration test using Testcontainers**

```java
package com.example.idempotency.store;

import com.example.idempotency.model.CachedResponse;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RedisIdempotencyStoreTest {

    @Container
    static RedisContainer redis = new RedisContainer("redis:7-alpine");

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    private RedisIdempotencyStore store;

    @BeforeEach
    void setUp() {
        store = new RedisIdempotencyStore(redisTemplate);
    }

    @Test
    void shouldReturnEmptyWhenKeyNotFound() {
        Optional<CachedResponse> result = store.get("nonexistent-key");
        assertThat(result).isEmpty();
    }

    @Test
    void shouldStoreAndRetrieveCachedResponse() {
        CachedResponse response = new CachedResponse(201, "{\"id\":1}", null);

        store.put("test-key", response, 1, TimeUnit.HOURS);

        Optional<CachedResponse> result = store.get("test-key");
        assertThat(result).isPresent();
        assertThat(result.get().getStatusCode()).isEqualTo(201);
        assertThat(result.get().getBody()).isEqualTo("{\"id\":1}");
    }

    @Test
    void shouldRespectTtl() throws InterruptedException {
        CachedResponse response = new CachedResponse(200, "{}", null);

        store.put("expiring-key", response, 1, TimeUnit.SECONDS);

        assertThat(store.get("expiring-key")).isPresent();
        Thread.sleep(1500);
        assertThat(store.get("expiring-key")).isEmpty();
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.example.idempotency.store.RedisIdempotencyStoreTest"`
Expected: FAIL — classes do not exist

**Step 3: Create IdempotencyStore interface**

```java
package com.example.idempotency.store;

import com.example.idempotency.model.CachedResponse;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

public interface IdempotencyStore {
    Optional<CachedResponse> get(String key);
    void put(String key, CachedResponse response, long ttl, TimeUnit timeUnit);
}
```

**Step 4: Implement RedisIdempotencyStore**

```java
package com.example.idempotency.store;

import com.example.idempotency.model.CachedResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class RedisIdempotencyStore implements IdempotencyStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisIdempotencyStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Optional<CachedResponse> get(String key) {
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, CachedResponse.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, CachedResponse response, long ttl, TimeUnit timeUnit) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, json, ttl, timeUnit);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize CachedResponse", e);
        }
    }
}
```

**Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.example.idempotency.store.RedisIdempotencyStoreTest"`
Expected: PASS (3 tests)

**Step 6: Commit**

```bash
git add src/
git commit -m "feat: add IdempotencyStore interface and Redis implementation"
```

---

### Task 5: Create IdempotencyConfig

**Files:**
- Create: `src/main/java/com/example/idempotency/config/IdempotencyConfig.java`

**Step 1: Create the configuration class**

```java
package com.example.idempotency.config;

import com.example.idempotency.store.IdempotencyStore;
import com.example.idempotency.store.RedisIdempotencyStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class IdempotencyConfig {

    @Bean
    public IdempotencyStore idempotencyStore(StringRedisTemplate redisTemplate) {
        return new RedisIdempotencyStore(redisTemplate);
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/java/com/example/idempotency/config/
git commit -m "feat: add IdempotencyConfig to wire up Redis store bean"
```

---

### Task 6: Implement IdempotencyAspect

**Files:**
- Create: `src/main/java/com/example/idempotency/aspect/IdempotencyAspect.java`
- Create: `src/test/java/com/example/idempotency/aspect/IdempotencyAspectTest.java`

**Step 1: Write the failing unit tests (mocked store)**

```java
package com.example.idempotency.aspect;

import com.example.idempotency.annotation.Idempotent;
import com.example.idempotency.exception.IdempotencyBodyMismatchException;
import com.example.idempotency.exception.IdempotencyKeyMissingException;
import com.example.idempotency.model.CachedResponse;
import com.example.idempotency.store.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyAspectTest {

    @Mock private IdempotencyStore store;
    @Mock private ProceedingJoinPoint joinPoint;
    @Mock private Idempotent idempotent;

    private IdempotencyAspect aspect;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        aspect = new IdempotencyAspect(store, objectMapper);
    }

    private void setUpRequest(String headerName, String headerValue, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (headerValue != null) {
            request.addHeader(headerName, headerValue);
        }
        if (body != null) {
            request.setContent(body.getBytes());
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void shouldThrowWhenMandatoryHeaderMissing() {
        setUpRequest("Idempotency-Key", null, null);
        when(idempotent.headerName()).thenReturn("Idempotency-Key");
        when(idempotent.mandatory()).thenReturn(true);

        assertThatThrownBy(() -> aspect.handleIdempotency(joinPoint, idempotent))
                .isInstanceOf(IdempotencyKeyMissingException.class);
    }

    @Test
    void shouldProceedWhenNonMandatoryHeaderMissing() throws Throwable {
        setUpRequest("Idempotency-Key", null, null);
        when(idempotent.headerName()).thenReturn("Idempotency-Key");
        when(idempotent.mandatory()).thenReturn(false);
        ResponseEntity<String> expected = ResponseEntity.ok("result");
        when(joinPoint.proceed()).thenReturn(expected);

        Object result = aspect.handleIdempotency(joinPoint, idempotent);

        assertThat(result).isEqualTo(expected);
        verifyNoInteractions(store);
    }

    @Test
    void shouldReturnCachedResponseOnHit() throws Throwable {
        setUpRequest("Idempotency-Key", "key-123", null);
        when(idempotent.headerName()).thenReturn("Idempotency-Key");
        when(idempotent.mandatory()).thenReturn(true);
        when(idempotent.keyPrefix()).thenReturn("orders");
        when(idempotent.includeBody()).thenReturn(false);

        CachedResponse cached = new CachedResponse(201, "{\"id\":1}", null);
        when(store.get("idempotency:orders:key-123")).thenReturn(Optional.of(cached));

        Object result = aspect.handleIdempotency(joinPoint, idempotent);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> responseEntity = (ResponseEntity<?>) result;
        assertThat(responseEntity.getStatusCode().value()).isEqualTo(201);
        verify(joinPoint, never()).proceed();
    }

    @Test
    void shouldProceedAndCacheOnMiss() throws Throwable {
        setUpRequest("Idempotency-Key", "key-456", null);
        when(idempotent.headerName()).thenReturn("Idempotency-Key");
        when(idempotent.mandatory()).thenReturn(true);
        when(idempotent.keyPrefix()).thenReturn("orders");
        when(idempotent.includeBody()).thenReturn(false);
        when(idempotent.ttl()).thenReturn(1L);
        when(idempotent.timeUnit()).thenReturn(TimeUnit.HOURS);

        when(store.get("idempotency:orders:key-456")).thenReturn(Optional.empty());
        ResponseEntity<String> controllerResponse = ResponseEntity.status(201).body("{\"id\":2}");
        when(joinPoint.proceed()).thenReturn(controllerResponse);

        Object result = aspect.handleIdempotency(joinPoint, idempotent);

        assertThat(result).isEqualTo(controllerResponse);
        verify(store).put(eq("idempotency:orders:key-456"), any(CachedResponse.class), eq(1L), eq(TimeUnit.HOURS));
    }

    @Test
    void shouldNotCacheNon2xxResponses() throws Throwable {
        setUpRequest("Idempotency-Key", "key-789", null);
        when(idempotent.headerName()).thenReturn("Idempotency-Key");
        when(idempotent.mandatory()).thenReturn(true);
        when(idempotent.keyPrefix()).thenReturn("");
        when(idempotent.includeBody()).thenReturn(false);

        when(store.get("idempotency::key-789")).thenReturn(Optional.empty());
        ResponseEntity<String> errorResponse = ResponseEntity.badRequest().body("error");
        when(joinPoint.proceed()).thenReturn(errorResponse);

        Object result = aspect.handleIdempotency(joinPoint, idempotent);

        assertThat(result).isEqualTo(errorResponse);
        verify(store, never()).put(anyString(), any(), anyLong(), any());
    }

    @Test
    void shouldThrowOnBodyMismatch() throws Throwable {
        setUpRequest("Idempotency-Key", "key-abc", "{\"amount\":100}");
        when(idempotent.headerName()).thenReturn("Idempotency-Key");
        when(idempotent.mandatory()).thenReturn(true);
        when(idempotent.keyPrefix()).thenReturn("pay");
        when(idempotent.includeBody()).thenReturn(true);

        // Cached with a different body hash
        CachedResponse cached = new CachedResponse(200, "{}", "different-hash");
        when(store.get(startsWith("idempotency:pay:key-abc:"))).thenReturn(Optional.empty());
        // Simulate: key without body hash has a cached entry with mismatched hash
        // The aspect builds key with body hash, so this tests the lookup-by-prefix scenario
        // Actually: the key includes the body hash, so a different body = different key = miss
        // Body mismatch detection needs a separate lookup by base key
        // Let me adjust: store the base key mapping too

        // For the body mismatch test, we need to think about the implementation:
        // Option A: Key always includes body hash when includeBody=true. Different body = different key = miss (no mismatch detection)
        // Option B: Key does NOT include body hash. Store body hash inside CachedResponse. On hit, compare hashes.
        // Design says Option B (store bodyHash in CachedResponse, key is idempotency:{prefix}:{headerVal})
        // Let me re-setup:
        when(store.get("idempotency:pay:key-abc")).thenReturn(Optional.of(cached));

        assertThatThrownBy(() -> aspect.handleIdempotency(joinPoint, idempotent))
                .isInstanceOf(IdempotencyBodyMismatchException.class);
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.example.idempotency.aspect.IdempotencyAspectTest"`
Expected: FAIL — IdempotencyAspect does not exist

**Step 3: Implement IdempotencyAspect**

Key design clarification for `includeBody`:
- The Redis key is always `idempotency:{prefix}:{headerVal}` (body hash is NOT part of the key)
- When `includeBody=true`, the body's SHA-256 hash is stored inside `CachedResponse.bodyHash`
- On cache hit with `includeBody=true`, compare the incoming body hash against the stored hash
- Mismatch → 422 Unprocessable Entity

```java
package com.example.idempotency.aspect;

import com.example.idempotency.annotation.Idempotent;
import com.example.idempotency.exception.IdempotencyBodyMismatchException;
import com.example.idempotency.exception.IdempotencyKeyMissingException;
import com.example.idempotency.model.CachedResponse;
import com.example.idempotency.store.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Aspect
@Component
public class IdempotencyAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);
    private static final String KEY_PREFIX = "idempotency";

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public IdempotencyAspect(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(idempotent)")
    public Object handleIdempotency(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        HttpServletRequest request = getCurrentRequest();

        String headerValue = request.getHeader(idempotent.headerName());

        if (headerValue == null || headerValue.isBlank()) {
            if (idempotent.mandatory()) {
                throw new IdempotencyKeyMissingException(idempotent.headerName());
            }
            return joinPoint.proceed();
        }

        String redisKey = buildKey(idempotent.keyPrefix(), headerValue);

        // Check cache
        Optional<CachedResponse> cached;
        try {
            cached = store.get(redisKey);
        } catch (Exception e) {
            log.warn("Redis unavailable for idempotency check, proceeding without: {}", e.getMessage());
            return joinPoint.proceed();
        }

        if (cached.isPresent()) {
            CachedResponse cachedResponse = cached.get();

            // Body mismatch check
            if (idempotent.includeBody() && cachedResponse.getBodyHash() != null) {
                String currentBodyHash = hashBody(request);
                if (!cachedResponse.getBodyHash().equals(currentBodyHash)) {
                    throw new IdempotencyBodyMismatchException(headerValue);
                }
            }

            return ResponseEntity
                    .status(cachedResponse.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(cachedResponse.getBody());
        }

        // Cache miss — proceed
        Object result = joinPoint.proceed();

        // Cache only 2xx ResponseEntity results
        if (result instanceof ResponseEntity<?> responseEntity) {
            HttpStatusCode status = responseEntity.getStatusCode();
            if (status.is2xxSuccessful()) {
                try {
                    String body = objectMapper.writeValueAsString(responseEntity.getBody());
                    String bodyHash = idempotent.includeBody() ? hashBody(request) : null;
                    CachedResponse toCache = new CachedResponse(status.value(), body, bodyHash);
                    store.put(redisKey, toCache, idempotent.ttl(), idempotent.timeUnit());
                } catch (Exception e) {
                    log.warn("Failed to cache idempotency response: {}", e.getMessage());
                }
            }
        }

        return result;
    }

    private String buildKey(String prefix, String headerValue) {
        if (prefix == null || prefix.isBlank()) {
            return KEY_PREFIX + ":" + headerValue;
        }
        return KEY_PREFIX + ":" + prefix + ":" + headerValue;
    }

    private String hashBody(HttpServletRequest request) {
        try {
            byte[] bodyBytes = request.getInputStream().readAllBytes();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bodyBytes);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash request body", e);
        }
    }

    private HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            throw new IllegalStateException("No current HTTP request context");
        }
        return attrs.getRequest();
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.example.idempotency.aspect.IdempotencyAspectTest"`
Expected: PASS (6 tests)

**Step 5: Commit**

```bash
git add src/
git commit -m "feat: implement IdempotencyAspect with AOP around advice"
```

---

### Task 7: End-to-End Integration Test

**Files:**
- Create: `src/test/java/com/example/idempotency/integration/IdempotencyIntegrationTest.java`
- Create: `src/test/java/com/example/idempotency/integration/TestController.java`

**Step 1: Write the integration test with a test controller**

```java
package com.example.idempotency.integration;

import com.example.idempotency.annotation.Idempotent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/test")
public class TestController {

    private final AtomicInteger callCount = new AtomicInteger(0);

    @PostMapping("/orders")
    @Idempotent(keyPrefix = "test-orders", ttl = 1, timeUnit = TimeUnit.MINUTES)
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody Map<String, Object> body) {
        int count = callCount.incrementAndGet();
        return ResponseEntity.status(201).body(Map.of("id", count, "data", body));
    }

    @PostMapping("/optional")
    @Idempotent(keyPrefix = "test-optional", mandatory = false)
    public ResponseEntity<Map<String, Object>> optionalIdempotency(@RequestBody Map<String, Object> body) {
        int count = callCount.incrementAndGet();
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PostMapping("/with-body-check")
    @Idempotent(keyPrefix = "test-body", includeBody = true, ttl = 1, timeUnit = TimeUnit.MINUTES)
    public ResponseEntity<Map<String, Object>> withBodyCheck(@RequestBody Map<String, Object> body) {
        int count = callCount.incrementAndGet();
        return ResponseEntity.ok(Map.of("count", count));
    }

    public int getCallCount() {
        return callCount.get();
    }

    public void resetCallCount() {
        callCount.set(0);
    }
}
```

```java
package com.example.idempotency.integration;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IdempotencyIntegrationTest {

    @Container
    static RedisContainer redis = new RedisContainer("redis:7-alpine");

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private TestController testController;

    @BeforeEach
    void setUp() {
        testController.resetCallCount();
    }

    @Test
    void shouldReturnCachedResponseOnDuplicateRequest() throws Exception {
        String body = "{\"item\":\"widget\"}";

        // First request
        mockMvc.perform(post("/test/orders")
                        .header("Idempotency-Key", "dup-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));

        // Duplicate request — same key, should return cached
        mockMvc.perform(post("/test/orders")
                        .header("Idempotency-Key", "dup-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));  // Same id, not 2

        // Controller was only called once
        assert testController.getCallCount() == 1;
    }

    @Test
    void shouldReturn400WhenMandatoryHeaderMissing() throws Exception {
        mockMvc.perform(post("/test/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"widget\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldProceedWithoutHeaderWhenNotMandatory() throws Exception {
        mockMvc.perform(post("/test/optional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":\"value\"}"))
                .andExpect(status().isOk());

        // Called once without idempotency
        assert testController.getCallCount() == 1;
    }

    @Test
    void shouldReturn422OnBodyMismatch() throws Exception {
        // First request with body A
        mockMvc.perform(post("/test/with-body-check")
                        .header("Idempotency-Key", "body-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100}"))
                .andExpect(status().isOk());

        // Same key, different body
        mockMvc.perform(post("/test/with-body-check")
                        .header("Idempotency-Key", "body-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":200}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void shouldAllowDifferentKeysIndependently() throws Exception {
        mockMvc.perform(post("/test/orders")
                        .header("Idempotency-Key", "key-A")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"a\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));

        mockMvc.perform(post("/test/orders")
                        .header("Idempotency-Key", "key-B")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"b\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(2));
    }
}
```

**Step 2: Run integration tests**

Run: `./gradlew test --tests "com.example.idempotency.integration.IdempotencyIntegrationTest"`
Expected: PASS (5 tests)

**Step 3: Run all tests together**

Run: `./gradlew test`
Expected: All tests pass

**Step 4: Commit**

```bash
git add src/test/
git commit -m "test: add end-to-end integration tests for idempotency"
```

---

### Task 8: Final Cleanup and Verification

**Step 1: Run full build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL

**Step 2: Verify all tests pass**

Run: `./gradlew test`
Expected: All tests pass (CachedResponseTest: 2, RedisIdempotencyStoreTest: 3, IdempotencyAspectTest: 6, IdempotencyIntegrationTest: 5 = 16 total)

**Step 3: Commit any remaining changes**

```bash
git status
# If clean, no commit needed
```
