package ru.Water_Tours.exceptions;


import java.time.Instant;

public record ExceptionResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {
}
