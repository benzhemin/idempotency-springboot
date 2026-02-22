package com.example.idempotency.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class IdempotencyKeyMissingException extends RuntimeException {
    public IdempotencyKeyMissingException(String headerName) {
        super("Missing required idempotency header: " + headerName);
    }
}
