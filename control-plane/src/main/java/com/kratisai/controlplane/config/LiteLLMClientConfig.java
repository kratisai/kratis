package com.kratisai.controlplane.config;

import com.kratisai.controlplane.client.litellm.LiteLLMClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class LiteLLMClientConfig {

    @Bean
    @ConditionalOnMissingBean
    public LiteLLMClient liteLLMClient(LiteLLMProperties properties) {
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeaders(headers -> headers.setBearerAuth(properties.getMasterKey()))
                .requestFactory(requestFactory(properties))
                .build();
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory =
                HttpServiceProxyFactory.builderFor(adapter).build();
        return factory.createClient(LiteLLMClient.class);
    }

    // A wedged LiteLLM otherwise inherits a multi-minute socket timeout and stalls provisioning.
    static SimpleClientHttpRequestFactory requestFactory(LiteLLMProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return requestFactory;
    }
}
