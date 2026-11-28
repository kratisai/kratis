package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.service.PublishException;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final int MAX_LOG_VALUE_LENGTH = 120;

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

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ValidationErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String reason = mismatchReason(ex);
        logger.warn(
                "Parameter type mismatch on '{}' with value '{}': {}",
                ex.getName(),
                logSafeValue(ex.getValue()),
                reason);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ValidationErrorResponse(reason, Map.of()));
    }

    static String logSafeValue(Object value) {
        String raw = String.valueOf(value);
        int limit = Math.min(raw.length(), MAX_LOG_VALUE_LENGTH);
        StringBuilder sanitized = new StringBuilder(limit + 3);
        for (int i = 0; i < limit; i++) {
            char ch = raw.charAt(i);
            int type = Character.getType(ch);
            if (Character.isISOControl(ch)
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                sanitized.append('_');
            } else {
                sanitized.append(ch);
            }
        }
        if (raw.length() > MAX_LOG_VALUE_LENGTH) {
            sanitized.append("...");
        }
        return sanitized.toString();
    }

    private static String mismatchReason(MethodArgumentTypeMismatchException ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (isSafeMismatchMessage(message)) {
                return message;
            }
            current = current.getCause();
        }
        Class<?> requiredType = ex.getRequiredType();
        String expectedType = requiredType == null ? "value" : requiredType.getSimpleName();
        return "Invalid value for parameter '" + ex.getName() + "' (expected " + expectedType + ")";
    }

    private static boolean isSafeMismatchMessage(String message) {
        if (message == null || message.isBlank() || message.length() > 120) {
            return false;
        }
        if (message.contains("No enum constant")
                || message.contains("com.kratisai.")
                || message.contains("Failed to convert")) {
            return false;
        }
        return message.chars().noneMatch(ch -> ch < 0x20 || ch == 0x7F || ch == '<' || ch == '>' || ch == '"');
    }

    @ExceptionHandler(PublishException.class)
    public ResponseEntity<ValidationErrorResponse> handlePublishException(PublishException ex) {
        logger.warn("Publish exception: {} - {}", ex.getErrorCode(), ex.getMessage());
        ValidationErrorResponse response = new ValidationErrorResponse(
                ex.getMessage(), Map.of("errorCode", ex.getErrorCode().name()));
        return ResponseEntity.status(ex.getErrorCode().httpStatus()).body(response);
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
