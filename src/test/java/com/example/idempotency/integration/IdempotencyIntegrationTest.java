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

import static org.assertj.core.api.Assertions.assertThat;
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
                .andExpect(jsonPath("$.id").value(1));

        // Controller was only called once
        assertThat(testController.getCallCount()).isEqualTo(1);
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

        assertThat(testController.getCallCount()).isEqualTo(1);
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
