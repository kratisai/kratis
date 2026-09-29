package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService("test-secret-key-that-is-at-least-256-bits-long-for-hs256", 3600000, 604800000);
    }

    @Test
    void generateAccessToken_shouldReturnValidToken() {
        UUID userId = UUID.randomUUID();
        String email = "test@example.com";

        String token = jwtService.generateAccessToken(userId, email);

        assertThat(token).isNotBlank();
        assertThat(jwtService.isTokenValid(token)).isTrue();
        assertThat(jwtService.getUserIdFromToken(token)).isEqualTo(userId);
        assertThat(jwtService.getEmailFromToken(token)).isEqualTo(email);
    }

    @Test
    void generateRefreshToken_shouldReturnValidToken() {
        UUID userId = UUID.randomUUID();
        String email = "test@example.com";

        String token = jwtService.generateRefreshToken(userId, email);

        assertThat(token).isNotBlank();
        assertThat(jwtService.isTokenValid(token)).isTrue();
        assertThat(jwtService.getUserIdFromToken(token)).isEqualTo(userId);
    }

    @Test
    void generateRefreshToken_shouldBeUniqueEvenWithinSameSecond() {
        UUID userId = UUID.randomUUID();
        String email = "test@example.com";

        String first = jwtService.generateRefreshToken(userId, email);
        String second = jwtService.generateRefreshToken(userId, email);

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void generateAccessToken_withExtraClaims_shouldIncludeClaims() {
        UUID userId = UUID.randomUUID();
        String email = "test@example.com";
        Map<String, Object> extraClaims = Map.of("role", "admin", "teamId", "team-123");

        String token = jwtService.generateAccessToken(userId, email, extraClaims);

        assertThat(token).isNotBlank();
        assertThat(jwtService.isTokenValid(token)).isTrue();
    }

    @Test
    void isTokenValid_withInvalidToken_shouldReturnFalse() {
        assertThat(jwtService.isTokenValid("invalid-token")).isFalse();
    }

    @Test
    void isTokenValid_withEmptyToken_shouldReturnFalse() {
        assertThat(jwtService.isTokenValid("")).isFalse();
    }

    @Test
    void parseClaims_withInvalidToken_shouldThrowException() {
        assertThatThrownBy(() -> jwtService.parseClaims("invalid-token")).isInstanceOf(Exception.class);
    }

    @Test
    void getAccessTokenExpiration_shouldReturnConfiguredValue() {
        assertThat(jwtService.getAccessTokenExpiration()).isEqualTo(3600000);
    }
}
