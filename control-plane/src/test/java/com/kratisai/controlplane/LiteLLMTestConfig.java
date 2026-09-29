package com.kratisai.controlplane;

import com.kratisai.controlplane.client.litellm.LiteLLMClient;
import com.kratisai.controlplane.config.LiteLLMProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@TestConfiguration
public class LiteLLMTestConfig {

    @Bean
    @Primary
    public TestLiteLLMClient testLiteLLMClient(LiteLLMProperties properties) {
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeaders(headers -> headers.setBearerAuth(properties.getMasterKey()))
                .build();
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory =
                HttpServiceProxyFactory.builderFor(adapter).build();
        LiteLLMClient realClient = factory.createClient(LiteLLMClient.class);
        return new TestLiteLLMClient(realClient);
    }
}
