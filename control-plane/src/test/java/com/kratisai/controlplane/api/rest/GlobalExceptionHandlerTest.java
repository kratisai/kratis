package com.kratisai.controlplane.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
}
