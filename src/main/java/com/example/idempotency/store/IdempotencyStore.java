package com.example.idempotency.store;

import com.example.idempotency.model.CachedResponse;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

public interface IdempotencyStore {
    Optional<CachedResponse> get(String key);
    void put(String key, CachedResponse response, long ttl, TimeUnit timeUnit);
    boolean tryLock(String key, long lockTtl, TimeUnit timeUnit);
}
