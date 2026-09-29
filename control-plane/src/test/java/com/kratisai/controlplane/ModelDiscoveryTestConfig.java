package com.kratisai.controlplane;

import com.kratisai.controlplane.client.ModelDiscoveryClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

// Dedicated RestTemplate so tests can bind a MockRestServiceServer without poisoning the shared
// application RestTemplate's request factory.
@TestConfiguration
public class ModelDiscoveryTestConfig {

    @Bean
    public RestTemplate modelDiscoveryRestTemplate() {
        return new RestTemplate();
    }

    @Bean
    @Primary
    public ModelDiscoveryClient testModelDiscoveryClient(
            @Qualifier("modelDiscoveryRestTemplate") RestTemplate restTemplate) {
        RestClient restClient = RestClient.builder()
                .requestFactory(
                        (uri, httpMethod) -> restTemplate.getRequestFactory().createRequest(uri, httpMethod))
                .build();
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory =
                HttpServiceProxyFactory.builderFor(adapter).build();
        return factory.createClient(ModelDiscoveryClient.class);
    }
}
