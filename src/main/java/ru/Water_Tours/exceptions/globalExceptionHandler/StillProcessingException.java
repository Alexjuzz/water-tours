package ru.Water_Tours.exceptions.globalExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import ru.Water_Tours.exceptions.ExceptionResponse;


import java.time.Instant;

@Getter
public class StillProcessingException extends  RuntimeException{
    private final String idempotencyKey;

    public StillProcessingException(String idempotencyKey) {
        super("Request with idempotency key " + idempotencyKey + " is still being processed.");
        this.idempotencyKey = idempotencyKey;
    }
    @ExceptionHandler(StillProcessingException.class)
    public ResponseEntity<ExceptionResponse> handleStillProcessing(
            StillProcessingException e,
            HttpServletRequest request) {

        ExceptionResponse body = new ExceptionResponse(
                Instant.now(),
                HttpStatus.CONFLICT.value(),
                "Still processing",
                "The original request is still being processed, retry shortly.",
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", "2")
                .body(body);
    }
}
