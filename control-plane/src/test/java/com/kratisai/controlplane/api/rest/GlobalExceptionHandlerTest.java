package com.kratisai.controlplane.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.model.UsageType;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = mock(HttpServletRequest.class);
    }

    @Test
    void handleResponseStatusException_includesRequestMethodAndUri() {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/executions");
        when(request.getQueryString()).thenReturn(null);

        ResponseStatusException ex = new ResponseStatusException(HttpStatus.BAD_REQUEST, "modelProviderId is required");

        ResponseEntity<GlobalExceptionHandler.ValidationErrorResponse> response =
                handler.handleResponseStatusException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("modelProviderId is required");
    }

    @Test
    void handleResponseStatusException_includesQueryStringWhenPresent() {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/v1/teams");
        when(request.getQueryString()).thenReturn("page=0&size=10");

        ResponseStatusException ex = new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");

        ResponseEntity<GlobalExceptionHandler.ValidationErrorResponse> response =
                handler.handleResponseStatusException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Not a member of this team");
    }

    @Test
    void handleResponseStatusException_returns500forServerError() {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/executions/terminate");
        when(request.getQueryString()).thenReturn(null);

        ResponseStatusException ex =
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to terminate execution");

        ResponseEntity<GlobalExceptionHandler.ValidationErrorResponse> response =
                handler.handleResponseStatusException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void handleTypeMismatch_returnsConverterMessageAsBadRequest() {
        MethodArgumentTypeMismatchException ex = typeMismatch(
                "WIDGET",
                new IllegalArgumentException(
                        "Unknown usage type: WIDGET. Accepted values: all, EXECUTION, INGESTION, TEAM_WIKI, CHAT"));

        ResponseEntity<GlobalExceptionHandler.ValidationErrorResponse> response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("Unknown usage type: WIDGET");
        assertThat(response.getBody().message()).doesNotContain("No enum constant");
        assertThat(response.getBody().message()).doesNotContain("com.kratisai.");
    }

    @Test
    void handleTypeMismatch_hidesSpringDefaultMessage() {
        MethodArgumentTypeMismatchException ex = typeMismatch(
                "WIDGET",
                new IllegalArgumentException("Failed to convert value of type 'java.lang.String' to required type "
                        + "'com.kratisai.controlplane.model.UsageType'"));

        ResponseEntity<GlobalExceptionHandler.ValidationErrorResponse> response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .contains("Invalid value for parameter 'usageType'")
                .contains("expected UsageType");
        assertThat(response.getBody().message()).doesNotContain("No enum constant");
        assertThat(response.getBody().message()).doesNotContain("com.kratisai.");
    }

    @Test
    void logSafeValue_stripsControlCharactersAndTruncates() {
        assertThat(GlobalExceptionHandler.logSafeValue("WIDGET\nFORGED\rERROR\tLINE"))
                .isEqualTo("WIDGET_FORGED_ERROR_LINE");
        assertThat(GlobalExceptionHandler.logSafeValue("line\u2028separator")).isEqualTo("line_separator");
        assertThat(GlobalExceptionHandler.logSafeValue("x".repeat(130)))
                .hasSize(123)
                .startsWith("x".repeat(120))
                .endsWith("...");
        assertThat(GlobalExceptionHandler.logSafeValue(null)).isEqualTo("null");
    }

    private static MethodArgumentTypeMismatchException typeMismatch(String value, Throwable cause) {
        try {
            Method method = DummyController.class.getMethod("handle", UsageType.class);
            return new MethodArgumentTypeMismatchException(
                    value, UsageType.class, "usageType", new MethodParameter(method, 0), cause);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    static class DummyController {
        public void handle(UsageType usageType) {}
    }
}
