package com.example.idempotency.aspect;

import com.example.idempotency.annotation.Idempotent;
import com.example.idempotency.exception.IdempotencyBodyMismatchException;
import com.example.idempotency.exception.IdempotencyKeyMissingException;
import com.example.idempotency.model.CachedResponse;
import com.example.idempotency.store.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        when(idempotent.keyPrefix()).thenReturn("pay");
        when(idempotent.includeBody()).thenReturn(true);

        CachedResponse cached = new CachedResponse(200, "{}", "different-hash");
        when(store.get("idempotency:pay:key-abc")).thenReturn(Optional.of(cached));

        assertThatThrownBy(() -> aspect.handleIdempotency(joinPoint, idempotent))
                .isInstanceOf(IdempotencyBodyMismatchException.class);
    }
}
