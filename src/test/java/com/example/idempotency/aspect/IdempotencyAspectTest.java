package com.example.idempotency.aspect;

import com.example.idempotency.annotation.Idempotent;
import com.example.idempotency.exception.IdempotencyBodyMismatchException;
import com.example.idempotency.exception.IdempotencyKeyMissingException;
import com.example.idempotency.model.CachedResponse;
import com.example.idempotency.store.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
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
    @Mock private MethodSignature methodSignature;

    private IdempotencyAspect aspect;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        aspect = new IdempotencyAspect(store, objectMapper);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void setUpRequest(String headerName, String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (headerValue != null) {
            request.addHeader(headerName, headerValue);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    // Dummy method used to supply @RequestBody parameter annotations to the mock MethodSignature
    @SuppressWarnings("unused")
    public void dummyEndpoint(@RequestBody Map<String, Object> body) {}

    private void setUpJoinPointWithBody(Object bodyArg) throws NoSuchMethodException {
        Method dummyMethod = this.getClass().getMethod("dummyEndpoint", Map.class);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(dummyMethod);
        when(joinPoint.getArgs()).thenReturn(new Object[]{bodyArg});
    }

    @Test
    void shouldThrowWhenMandatoryHeaderMissing() {
        setUpRequest("Idempotency-Key", null);
        when(idempotent.headerName()).thenReturn("Idempotency-Key");
        when(idempotent.mandatory()).thenReturn(true);

        assertThatThrownBy(() -> aspect.handleIdempotency(joinPoint, idempotent))
                .isInstanceOf(IdempotencyKeyMissingException.class);
    }

    @Test
    void shouldProceedWhenNonMandatoryHeaderMissing() throws Throwable {
        setUpRequest("Idempotency-Key", null);
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
        setUpRequest("Idempotency-Key", "key-123");
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
        setUpRequest("Idempotency-Key", "key-456");
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
        setUpRequest("Idempotency-Key", "key-789");
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
        setUpRequest("Idempotency-Key", "key-abc");
        when(idempotent.headerName()).thenReturn("Idempotency-Key");
        when(idempotent.keyPrefix()).thenReturn("pay");
        when(idempotent.includeBody()).thenReturn(true);

        // Set up joinPoint to return a parsed @RequestBody argument
        Map<String, Object> requestBody = Map.of("amount", 100);
        setUpJoinPointWithBody(requestBody);

        // Cached response has a different body hash
        CachedResponse cached = new CachedResponse(200, "{}", "different-hash");
        when(store.get("idempotency:pay:key-abc")).thenReturn(Optional.of(cached));

        assertThatThrownBy(() -> aspect.handleIdempotency(joinPoint, idempotent))
                .isInstanceOf(IdempotencyBodyMismatchException.class);
    }

    @Test
    void shouldCacheBodyHashWhenIncludeBodyEnabled() throws Throwable {
        setUpRequest("Idempotency-Key", "key-body-1");
        when(idempotent.headerName()).thenReturn("Idempotency-Key");
        when(idempotent.keyPrefix()).thenReturn("orders");
        when(idempotent.includeBody()).thenReturn(true);
        when(idempotent.ttl()).thenReturn(1L);
        when(idempotent.timeUnit()).thenReturn(TimeUnit.HOURS);

        Map<String, Object> requestBody = Map.of("item", "widget");
        setUpJoinPointWithBody(requestBody);

        when(store.get("idempotency:orders:key-body-1")).thenReturn(Optional.empty());
        ResponseEntity<Map<String, Object>> controllerResponse = ResponseEntity.ok(Map.of("id", 1));
        when(joinPoint.proceed()).thenReturn(controllerResponse);

        aspect.handleIdempotency(joinPoint, idempotent);

        verify(store).put(eq("idempotency:orders:key-body-1"),
                argThat(cached -> cached.getBodyHash() != null && !cached.getBodyHash().isEmpty()),
                eq(1L), eq(TimeUnit.HOURS));
    }
}
