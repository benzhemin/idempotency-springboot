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
