package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.client.ModelDiscoveryClient;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.service.ProviderPricing.Rate;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProviderPricingTest {

    private static final URI KILO_MODELS = URI.create("https://api.kilo.ai/api/gateway/models");
    private static final String MODEL = "anthropic/claude-sonnet-4.5";

    private static final String LISTING = """
            {"data":[
              {"id":"anthropic/claude-sonnet-4.5","pricing":{"prompt":"0.000003","completion":"0.000015"}},
              {"id":"openai/gpt-5","pricing":{"prompt":"0.000001","completion":"0.000002"}}
            ]}
            """;

    @Mock
    private ModelDiscoveryClient modelDiscoveryClient;

    private MutableClock clock;
    private ProviderPricing pricing;
    private ModelProvider kilo;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-24T00:00:00Z"));
        pricing = new ProviderPricing(modelDiscoveryClient, clock);
        kilo = new ModelProvider("Kilo", ProviderType.KILO, "kilo-key", null);
    }

    @Test
    void resolve_coldCache_shouldFetchPublishedListingAndReturnRequestedRate() {
        when(modelDiscoveryClient.getModels(KILO_MODELS, "Bearer kilo-key")).thenReturn(LISTING);

        assertThat(pricing.rateFor(kilo, MODEL)).contains(new Rate(0.000003, 0.000015));

        verify(modelDiscoveryClient, times(1)).getModels(KILO_MODELS, "Bearer kilo-key");
    }

    @Test
    void resolve_coldCache_shouldCacheEveryPublishedModelFromOneFetch() {
        when(modelDiscoveryClient.getModels(any(), anyString())).thenReturn(LISTING);
        pricing.rateFor(kilo, MODEL);

        assertThat(pricing.rateFor(kilo, "openai/gpt-5")).contains(new Rate(0.000001, 0.000002));

        verify(modelDiscoveryClient, times(1)).getModels(any(), anyString());
    }

    @Test
    void resolve_freshEntry_shouldNotFetch() {
        when(modelDiscoveryClient.getModels(any(), anyString())).thenReturn(LISTING);
        pricing.rateFor(kilo, MODEL);
        clock.advance(ProviderPricing.TTL.minusSeconds(1));

        assertThat(pricing.rateFor(kilo, MODEL)).isPresent();

        verify(modelDiscoveryClient, times(1)).getModels(any(), anyString());
    }

    @Test
    void resolve_expiredEntry_shouldRefetchAndReplace() {
        when(modelDiscoveryClient.getModels(any(), anyString())).thenReturn(LISTING, """
                {"data":[{"id":"anthropic/claude-sonnet-4.5","pricing":{"prompt":"0.000004","completion":"0.000020"}}]}
                """);
        pricing.rateFor(kilo, MODEL);
        clock.advance(ProviderPricing.TTL);

        assertThat(pricing.rateFor(kilo, MODEL)).contains(new Rate(0.000004, 0.000020));

        verify(modelDiscoveryClient, times(2)).getModels(any(), anyString());
    }

    @Test
    void resolve_modelAbsentFromListing_shouldReturnEmpty() {
        when(modelDiscoveryClient.getModels(any(), anyString())).thenReturn(LISTING);

        assertThat(pricing.rateFor(kilo, "unlisted/model")).isEmpty();
    }

    @Test
    void resolve_refetchFailsWithExpiredEntry_shouldKeepLastKnownRate() {
        when(modelDiscoveryClient.getModels(any(), anyString()))
                .thenReturn(LISTING)
                .thenThrow(new RuntimeException("gateway unreachable"));
        pricing.rateFor(kilo, MODEL);
        clock.advance(ProviderPricing.TTL.plusSeconds(1));

        assertThat(pricing.rateFor(kilo, MODEL)).contains(new Rate(0.000003, 0.000015));
    }

    @Test
    void resolve_fetchFailsWithoutCache_shouldReturnEmptyAndRetryNextTime() {
        when(modelDiscoveryClient.getModels(any(), anyString())).thenThrow(new RuntimeException("unreachable"));

        assertThat(pricing.rateFor(kilo, MODEL)).isEmpty();
        assertThat(pricing.rateFor(kilo, MODEL)).isEmpty();

        verify(modelDiscoveryClient, times(2)).getModels(any(), anyString());
    }

    @Test
    void resolve_unparseableListing_shouldReturnEmpty() {
        when(modelDiscoveryClient.getModels(any(), anyString())).thenReturn("not json");

        assertThat(pricing.rateFor(kilo, MODEL)).isEmpty();
    }

    @Test
    void resolve_listingWithoutDataArray_shouldReturnEmpty() {
        when(modelDiscoveryClient.getModels(any(), anyString())).thenReturn("{\"other\":1}");

        assertThat(pricing.rateFor(kilo, MODEL)).isEmpty();
    }

    @Test
    void resolve_shouldSkipUnknownIncompleteOrInvalidRates() {
        when(modelDiscoveryClient.getModels(any(), anyString())).thenReturn("""
                {"data":[
                  {"id":"kilo-auto/balanced","pricing":{"prompt":"-1","completion":"-1"}},
                  {"id":"partial","pricing":{"prompt":"0.000001"}},
                  {"id":"no-pricing"},
                  {"id":"garbage","pricing":{"prompt":"n/a","completion":"0.1"}},
                  {"id":"object","pricing":{"prompt":{},"completion":"0.1"}},
                  {"id":"free:free","pricing":{"prompt":0,"completion":"0"}}
                ]}
                """);

        assertThat(pricing.rateFor(kilo, "kilo-auto/balanced")).isEmpty();
        assertThat(pricing.rateFor(kilo, "partial")).isEmpty();
        assertThat(pricing.rateFor(kilo, "no-pricing")).isEmpty();
        assertThat(pricing.rateFor(kilo, "garbage")).isEmpty();
        assertThat(pricing.rateFor(kilo, "object")).isEmpty();
        assertThat(pricing.rateFor(kilo, "free:free")).contains(new Rate(0.0, 0.0));
    }

    @Test
    void resolve_litellmSourcedProvider_shouldNotFetch() {
        ModelProvider openAi = new ModelProvider("OpenAI", ProviderType.OPENAI, "sk-key", null);

        assertThat(pricing.rateFor(openAi, "gpt-4o")).isEmpty();

        verify(modelDiscoveryClient, never()).getModels(any(), anyString());
    }

    @Test
    void rate_rejectsUnknownOrNonFiniteValues() {
        assertThatThrownBy(() -> new Rate(-1.0, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rate(0.0, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
