package com.kratisai.controlplane.config;

import com.kratisai.controlplane.client.BitbucketApiClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class BitbucketApiClientConfig {

    @Bean
    public BitbucketApiClient bitbucketApiClient() {
        RestClient restClient = RestClient.builder()
                .baseUrl("https://api.bitbucket.org/2.0")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory =
                HttpServiceProxyFactory.builderFor(adapter).build();

        return factory.createClient(BitbucketApiClient.class);
    }
}
