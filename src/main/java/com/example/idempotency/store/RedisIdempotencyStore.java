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

    @Override
    public boolean tryLock(String key, long lockTtl, TimeUnit timeUnit) {
        String lockKey = key + ":lock";
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "PROCESSING", lockTtl, timeUnit);
        return Boolean.TRUE.equals(acquired);
    }
}
