package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

class SecurityUtilTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getCurrentUserId_withValidAuthentication_shouldReturnUserId() {
        UUID userId = UUID.randomUUID();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId.toString(), null, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        UUID result = SecurityUtil.getCurrentUserId();

        assertThat(result).isEqualTo(userId);
    }

    @Test
    void getCurrentUserId_withNullAuthentication_shouldThrowUnauthorized() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(SecurityUtil::getCurrentUserId)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void getCurrentUserId_withNullPrincipal_shouldThrowUnauthorized() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(null, null);
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThatThrownBy(SecurityUtil::getCurrentUserId)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
