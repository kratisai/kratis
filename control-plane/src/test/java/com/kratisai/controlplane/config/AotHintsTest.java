package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.client.ModelDiscoveryClient;
import com.kratisai.controlplane.planningagent.AgentThinking;
import com.kratisai.controlplane.planningagent.DimensionTool.DimensionDetail;
import com.kratisai.controlplane.planningagent.DimensionTool.DimensionOverview;
import com.kratisai.controlplane.planningagent.DimensionTool.PatternSummary;
import com.kratisai.controlplane.planningagent.DimensionTool.RankedFile;
import com.kratisai.controlplane.planningagent.RepositoryTool.DependenciesResponse;
import com.kratisai.controlplane.planningagent.RepositoryTool.RepositorySummary;
import com.kratisai.controlplane.planningagent.WikiTool.WikiPageSummary;
import com.kratisai.controlplane.planningagent.WikiTool.WikiSearchResponse;
import com.kratisai.controlplane.planningagent.WikiTool.WikiSearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

@DisplayName("AotHints")
class AotHintsTest {

    @Test
    @DisplayName("registers JDK proxy for ModelDiscoveryClient")
    void registersJdkProxy() {
        RuntimeHints hints = new RuntimeHints();
        AotHints aotHints = new AotHints();

        aotHints.registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.proxies().forInterfaces(ModelDiscoveryClient.class))
                .accepts(hints);
    }

    @Test
    @DisplayName("registers reflection hints for planning agent models and DTOs")
    void registersReflectionHints() {
        RuntimeHints hints = new RuntimeHints();
        AotHints aotHints = new AotHints();

        aotHints.registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection().onType(DependenciesResponse.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(RepositorySummary.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(DimensionOverview.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(DimensionDetail.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(RankedFile.class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(PatternSummary.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(WikiPageSummary.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(WikiSearchResponse.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(WikiSearchResult.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(AgentThinking.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TypeReference.of("okhttp3.internal.http.RetryAndFollowUpInterceptor$Companion")))
                .accepts(hints);
    }

    @Test
    @DisplayName("registers resource hints for OkHttp PublicSuffixDatabase")
    void registersResourceHints() {
        RuntimeHints hints = new RuntimeHints();
        AotHints aotHints = new AotHints();

        aotHints.registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.resource()
                        .forResource("okhttp3/internal/publicsuffix/PublicSuffixDatabase.gz"))
                .accepts(hints);
    }
}
