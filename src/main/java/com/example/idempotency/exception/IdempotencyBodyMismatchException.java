package com.example.idempotency.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class IdempotencyBodyMismatchException extends RuntimeException {
    public IdempotencyBodyMismatchException(String idempotencyKey) {
        super("Idempotency key '" + idempotencyKey + "' was already used with a different request body");
    }
}
