package com.kratisai.controlplane.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.client.ModelDiscoveryClient;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.PricingSource;
import com.kratisai.controlplane.model.ProviderType;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProviderPricing {

    private static final Logger logger = LoggerFactory.getLogger(ProviderPricing.class);

    static final Duration TTL = Duration.ofHours(12);

    public record Rate(double inputCostPerToken, double outputCostPerToken) {

        public Rate {
            requireValidRate(inputCostPerToken, "inputCostPerToken");
            requireValidRate(outputCostPerToken, "outputCostPerToken");
        }

        private static void requireValidRate(double rate, String name) {
            if (!Double.isFinite(rate) || rate < 0) {
                throw new IllegalArgumentException(name + " must be a finite non-negative rate, got " + rate);
            }
        }
    }

    private record Key(ProviderType providerType, String baseUrl, String modelName) {

        Key {
            Objects.requireNonNull(providerType, "providerType is required");
            Objects.requireNonNull(baseUrl, "baseUrl is required");
            Objects.requireNonNull(modelName, "modelName is required");
            baseUrl = stripTrailingSlashes(baseUrl);
        }

        private static String stripTrailingSlashes(String url) {
            String result = url;
            while (result.endsWith("/")) {
                result = result.substring(0, result.length() - 1);
            }
            return result;
        }
    }

    private record Entry(Rate rate, Instant fetchedAt) {}

    private final ModelDiscoveryClient modelDiscoveryClient;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    @Autowired
    public ProviderPricing(ModelDiscoveryClient modelDiscoveryClient) {
        this(modelDiscoveryClient, Clock.systemUTC());
    }

    ProviderPricing(ModelDiscoveryClient modelDiscoveryClient, Clock clock) {
        this.modelDiscoveryClient = modelDiscoveryClient;
        this.clock = clock;
    }

    public Optional<Rate> rateFor(ModelProvider provider, String modelName) {
        if (provider.getProviderType().getPricingSource() != PricingSource.PROVIDER_API) {
            return Optional.empty();
        }
        String baseUrl = ModelProviderUrlResolver.resolveDirectBaseUrl(provider);
        Key key = new Key(provider.getProviderType(), baseUrl, modelName);
        Optional<Entry> cached = Optional.ofNullable(entries.get(key));
        if (cached.isPresent()
                && clock.instant().isBefore(cached.get().fetchedAt().plus(TTL))) {
            return Optional.of(cached.get().rate());
        }
        refresh(provider, baseUrl);
        return Optional.ofNullable(entries.get(key)).map(Entry::rate);
    }

    private void refresh(ModelProvider provider, String baseUrl) {
        try {
            String response =
                    modelDiscoveryClient.getModels(URI.create(baseUrl + "/models"), "Bearer " + provider.getApiKey());
            Instant fetchedAt = clock.instant();
            for (Map.Entry<String, Rate> published : parse(response).entrySet()) {
                entries.put(
                        new Key(provider.getProviderType(), baseUrl, published.getKey()),
                        new Entry(published.getValue(), fetchedAt));
            }
        } catch (RuntimeException e) {
            logger.warn(
                    "Could not refresh published pricing for provider '{}'; using last known rates. Cause: {}",
                    provider.getDisplayName(),
                    e.getMessage());
        }
    }

    private Map<String, Rate> parse(String response) {
        Map<String, Rate> rates = new LinkedHashMap<>();
        try {
            for (JsonNode model : objectMapper.readTree(response).path("data")) {
                publishedRate(model.path("pricing"))
                        .ifPresent(rate -> rates.put(model.path("id").asText(), rate));
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse published pricing: " + e.getMessage(), e);
        }
        return rates;
    }

    // Gateways publish -1 for auto-routed models whose price varies per request; that is unknown, not free.
    private static Optional<Rate> publishedRate(JsonNode pricing) {
        Double input = rate(pricing.path("prompt"));
        Double output = rate(pricing.path("completion"));
        if (input == null || output == null) {
            return Optional.empty();
        }
        return Optional.of(new Rate(input, output));
    }

    private static Double rate(JsonNode value) {
        if (!value.isNumber() && !value.isTextual()) {
            return null;
        }
        try {
            double rate = Double.parseDouble(value.asText());
            return Double.isFinite(rate) && rate >= 0 ? rate : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
