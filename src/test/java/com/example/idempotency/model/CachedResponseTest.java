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
