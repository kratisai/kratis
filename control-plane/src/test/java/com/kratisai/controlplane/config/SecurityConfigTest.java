package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class SecurityConfigTest {

    @Test
    void corsConfigurationSource_withWildcard_allowsAllOrigins() {
        SecurityConfig config = new SecurityConfig("*");
        CorsConfigurationSource source = config.corsConfigurationSource();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader("Origin", "https://example.com");

        CorsConfiguration corsConfig = source.getCorsConfiguration(request);
        assertThat(corsConfig).isNotNull();
        assertThat(corsConfig.getAllowedOriginPatterns()).containsExactly("*");
        assertThat(corsConfig.getAllowCredentials()).isTrue();
        assertThat(corsConfig.getAllowedMethods())
                .containsExactly("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD");
        assertThat(corsConfig.getAllowedHeaders()).containsExactly("*");
        assertThat(corsConfig.checkOrigin("https://example.com")).isEqualTo("https://example.com");
    }

    @Test
    void corsConfigurationSource_withEmptyString_defaultsToWildcard() {
        SecurityConfig config = new SecurityConfig("");
        CorsConfigurationSource source = config.corsConfigurationSource();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader("Origin", "https://example.com");

        CorsConfiguration corsConfig = source.getCorsConfiguration(request);
        assertThat(corsConfig).isNotNull();
        assertThat(corsConfig.getAllowedOriginPatterns()).containsExactly("*");
    }

    @Test
    void corsConfigurationSource_withSpecificOrigins_enforcesWhitelist() {
        SecurityConfig config = new SecurityConfig(" https://kratis.example.com , http://localhost:3000 ");
        CorsConfigurationSource source = config.corsConfigurationSource();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader("Origin", "https://kratis.example.com");

        CorsConfiguration corsConfig = source.getCorsConfiguration(request);
        assertThat(corsConfig).isNotNull();
        assertThat(corsConfig.getAllowedOrigins())
                .containsExactly("https://kratis.example.com", "http://localhost:3000");
        assertThat(corsConfig.checkOrigin("https://kratis.example.com")).isEqualTo("https://kratis.example.com");
        assertThat(corsConfig.checkOrigin("http://localhost:3000")).isEqualTo("http://localhost:3000");
        assertThat(corsConfig.checkOrigin("https://evil.com")).isNull();
    }
}
