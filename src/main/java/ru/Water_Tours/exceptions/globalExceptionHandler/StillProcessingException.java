package ru.Water_Tours.exceptions.globalExceptionHandler;

import lombok.Getter;

@Getter
public class StillProcessingException extends RuntimeException {
    private final String idempotencyKey;

    public StillProcessingException(String idempotencyKey) {
        super("Request with idempotency key " + idempotencyKey + " is still being processed.");
        this.idempotencyKey = idempotencyKey;
    }
}
