package com.example.idempotency.exception;

/**
 * Thrown when an idempotency key was already used with a different request body.
 * HTTP mapping (e.g. 422) is handled by the web layer.
 */
public class IdempotencyBodyMismatchException extends RuntimeException {
    public IdempotencyBodyMismatchException(String idempotencyKey) {
        super("Idempotency key '" + idempotencyKey + "' was already used with a different request body");
    }
}
