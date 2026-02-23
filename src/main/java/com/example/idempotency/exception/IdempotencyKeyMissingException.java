package com.example.idempotency.exception;

/**
 * Thrown when a required idempotency header is missing.
 * HTTP mapping (e.g. 400) is handled by the web layer.
 */
public class IdempotencyKeyMissingException extends RuntimeException {
    public IdempotencyKeyMissingException(String headerName) {
        super("Missing required idempotency header: " + headerName);
    }
}
