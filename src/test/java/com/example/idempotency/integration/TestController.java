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
