---
name: external-http-clients
description: "Guidelines and patterns for declaring, configuring, and testing external HTTP clients in the control-plane using Spring 6 Declarative Interfaces. Use when integrating third-party services (GitHub, GitLab, etc.) via HTTP/REST APIs."
---

## 1. Declarative Interface Setup

Define external service APIs using Spring's HTTP Interface annotations rather than low-level `RestClient` or `WebClient` requests. Declare them as interfaces:

```java
package com.kratisai.controlplane.service;

import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;

public interface ExternalServiceClient {

    @GetExchange("/v1/resource/{id}")
    ResponseEntity<String> getResource(
            @PathVariable("id") String id,
            @RequestHeader("Authorization") String token);

    @PostExchange("/v1/resource")
    ResponseEntity<String> createResource(
            @RequestBody MyRequestDto body,
            @RequestParam("query") String query);
}
```

## 2. Configuration & Beans Registration

Wire the interface dynamically using `RestClient` and `HttpServiceProxyFactory` inside a `@Configuration` class:

```java
package com.kratisai.controlplane.config;

import org.springframework.context.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import com.kratisai.controlplane.service.ExternalServiceClient;

@Configuration
public class ExternalClientConfig {

    @Bean
    public ExternalServiceClient externalServiceClient() {
        RestClient restClient = RestClient.builder()
                .baseUrl("https://api.external-service.com")
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
        
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();
        
        return factory.createClient(ExternalServiceClient.class);
    }
}
```

## 3. Testing & Fake Mock Setup (Mandatory)

Never let automated tests hit real external APIs. Every external client **MUST** have a corresponding Fake test implementation registered as a `@Primary` bean in `@TestConfiguration` under test slices:

```java
package com.kratisai.controlplane;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.ResponseEntity;
import com.kratisai.controlplane.service.ExternalServiceClient;

@TestConfiguration
public class FakeExternalServiceClientConfig {

    public static class FakeExternalServiceClient implements ExternalServiceClient {
        private static boolean shouldThrow = false;
        private static String mockResponse = "{\"status\":\"ok\"}";

        public static void setShouldThrow(boolean throwVal) { shouldThrow = throwVal; }
        public static void setMockResponse(String resp) { mockResponse = resp; }
        public static void reset() {
            shouldThrow = false;
            mockResponse = "{\"status\":\"ok\"}";
        }

        @Override
        public ResponseEntity<String> getResource(String id, String token) {
            if (shouldThrow) return ResponseEntity.internalServerError().build();
            return ResponseEntity.ok(mockResponse);
        }

        @Override
        public ResponseEntity<String> createResource(MyRequestDto body, String query) {
            if (shouldThrow) return ResponseEntity.internalServerError().build();
            return ResponseEntity.ok("{\"created\": true}");
        }
    }

    @Bean
    @Primary
    public ExternalServiceClient fakeExternalServiceClient() {
        return new FakeExternalServiceClient();
    }
}
```

Ensure this config is loaded in `@SpringIntegrationTest` by registering the fake client configuration in `SpringIntegrationTest.java`.
