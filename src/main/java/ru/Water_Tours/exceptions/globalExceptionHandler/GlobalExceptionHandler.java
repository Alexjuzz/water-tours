package ru.Water_Tours.exceptions.globalExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import ru.Water_Tours.exceptions.ExceptionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.NoSuchElementException;
import ru.Water_Tours.exceptions.globalExceptionHandler.StillProcessingException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ExceptionResponse> handleNoSuchElementException(NoSuchElementException e, HttpServletRequest request) {
        ExceptionResponse exceptionResponse = new ExceptionResponse(
                java.time.Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                "not found",
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(exceptionResponse);
    }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ExceptionResponse> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException e,
            HttpServletRequest request) {

        ExceptionResponse body = new ExceptionResponse(
                Instant.now(),
                HttpStatus.FORBIDDEN.value(),
                "Access denied",
                "Invalid or missing access token",
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ExceptionResponse> handleIllegalArgumentException(IllegalArgumentException e, HttpServletRequest request) {
        ExceptionResponse exceptionResponse = new ExceptionResponse(
                java.time.Instant.now(),
                HttpStatus.CONFLICT.value(),
                "Invalid request",
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exceptionResponse);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ExceptionResponse> handleIllegalStateException(IllegalStateException e, HttpServletRequest request) {

        ExceptionResponse exceptionResponse = new ExceptionResponse(
                java.time.Instant.now(),
                HttpStatus.CONFLICT.value(),
                "Invalid state",
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exceptionResponse);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ExceptionResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException e, HttpServletRequest request) {

        StringBuilder sb = new StringBuilder();
        for (FieldError er : e.getBindingResult().getFieldErrors()) {
            if (!sb.isEmpty()) sb.append("; ");
            sb.append(er.getField()).append(": ").append(er.getDefaultMessage());

        }
        String message = sb.isEmpty() ? "Validation error" : sb.toString();
        ExceptionResponse body = new ExceptionResponse(
                java.time.Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Validation error",
                message,
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
    @ExceptionHandler
    public ResponseEntity<ExceptionResponse> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException ex, HttpServletRequest request){
        String message = String.format("Invalid value '%s' for parameter '%s'.",
                ex.getValue(), ex.getName());

        ExceptionResponse exceptionResponse = new ExceptionResponse(
                java.time.Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Invalid parameter type",
                message,
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exceptionResponse);
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ExceptionResponse> handleNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException e,
            HttpServletRequest request) {

        ExceptionResponse body = new ExceptionResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Malformed request body",
                "Request body is malformed or contains invalid values",
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(StillProcessingException.class)
    public ResponseEntity<ExceptionResponse> handleStillProcessing(StillProcessingException e, HttpServletRequest request) {
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

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ExceptionResponse> handleMissingParameter(
            org.springframework.web.bind.MissingServletRequestParameterException e,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ExceptionResponse(
                Instant.now(), HttpStatus.BAD_REQUEST.value(), "Missing parameter",
                "Required parameter is missing: " + e.getParameterName(), request.getRequestURI()));
    }
    @ExceptionHandler(ru.Water_Tours.ticket.service.PaymentProviderException.class)
    public ResponseEntity<ExceptionResponse> handlePaymentProvider(
            ru.Water_Tours.ticket.service.PaymentProviderException e, HttpServletRequest request) {
        log.warn("Payment provider operation failed on {}: {}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "60")
                .body(new ExceptionResponse(Instant.now(), 503, "Payment verification unavailable",
                        "Payment is not confirmed yet. Please retry later.", request.getRequestURI()));
    }

    @ExceptionHandler(ru.Water_Tours.ticket.service.TicketEmailException.class)
    public ResponseEntity<ExceptionResponse> handleTicketEmail(
            ru.Water_Tours.ticket.service.TicketEmailException e, HttpServletRequest request) {
        log.warn("Ticket email delivery failed on {}: {}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "120")
                .body(new ExceptionResponse(Instant.now(), HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "Mail delivery unavailable",
                        "Письмо отправить не удалось. Билет доступен по ссылке, попробуйте отправку позже.",
                        request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ExceptionResponse> handleGeneralException(Exception e, HttpServletRequest request) {
        // Полный стектрейс только в логи
        log.error("Unhandled exception on {}", request.getRequestURI(), e);

        ExceptionResponse exceptionResponse = new ExceptionResponse(
                java.time.Instant.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected error occurred",
                "Внутренняя ошибка сервера. Обратитесь в поддержку.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(exceptionResponse);
    }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Void> handleMissingResource() {
        return ResponseEntity.notFound().build();
    }}

