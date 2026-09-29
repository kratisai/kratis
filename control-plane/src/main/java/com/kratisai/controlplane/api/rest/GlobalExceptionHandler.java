package com.kratisai.controlplane.api.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Schema(description = "Error response containing field validation errors")
    public record ValidationErrorResponse(
            @Schema(description = "Error message") String message,

            @Schema(description = "Map of field names to their validation errors")
            Map<String, String> errors) {}

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.put(
                        error.getField(),
                        error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid value"));
        ValidationErrorResponse response = new ValidationErrorResponse("Validation failed", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ValidationErrorResponse> handleResponseStatusException(
            ResponseStatusException ex, HttpServletRequest request) {
        String requestDetails = String.format(
                "%s %s%s",
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString() != null ? "?" + request.getQueryString() : "");

        if (ex.getStatusCode().is5xxServerError()) {
            logger.error("Response status exception on {}: {} {}", requestDetails, ex.getStatusCode(), ex.getReason());
        } else {
            logger.warn("Response status exception on {}: {} {}", requestDetails, ex.getStatusCode(), ex.getReason());
        }

        ValidationErrorResponse response = new ValidationErrorResponse(ex.getReason(), Map.of());
        return ResponseEntity.status(ex.getStatusCode()).body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ValidationErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        ValidationErrorResponse response = new ValidationErrorResponse(ex.getMessage(), Map.of());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ValidationErrorResponse> handleRuntimeException(RuntimeException ex) {
        logger.error("Unexpected error occurred", ex);
        ValidationErrorResponse response = new ValidationErrorResponse(ex.getMessage(), Map.of());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
