package com.example.idempotency.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps idempotency exceptions to HTTP responses. Keeps exception types free of
 * transport concerns; the web layer owns status codes and response shape.
 */
@RestControllerAdvice
public class IdempotencyExceptionHandler {

    @ExceptionHandler(IdempotencyKeyMissingException.class)
    public ResponseEntity<ErrorBody> handleMissingKey(IdempotencyKeyMissingException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorBody(ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyBodyMismatchException.class)
    public ResponseEntity<ErrorBody> handleBodyMismatch(IdempotencyBodyMismatchException ex) {
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorBody(ex.getMessage()));
    }

    public record ErrorBody(String message) {}
}
