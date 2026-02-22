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
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.security.MessageDigest;
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
        String bodyHash = idempotent.includeBody() ? hashRequestBody(joinPoint) : null;

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
            if (idempotent.includeBody() && cachedResponse.getBodyHash() != null && bodyHash != null) {
                if (!cachedResponse.getBodyHash().equals(bodyHash)) {
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

    private String hashRequestBody(ProceedingJoinPoint joinPoint) {
        Object body = extractRequestBody(joinPoint);
        if (body == null) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(body);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash request body", e);
        }
    }

    private Object extractRequestBody(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < paramAnnotations.length; i++) {
            for (Annotation annotation : paramAnnotations[i]) {
                if (annotation instanceof RequestBody) {
                    return args[i];
                }
            }
        }
        return null;
    }

    private HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            throw new IllegalStateException("No current HTTP request context");
        }
        return attrs.getRequest();
    }
}
