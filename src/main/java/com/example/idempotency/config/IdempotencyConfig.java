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
