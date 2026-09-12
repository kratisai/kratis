package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.api.wsdto.ActivityKind;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.client.ModelDiscoveryClient;
import com.kratisai.controlplane.ingestion.parse.DimensionDiscoveryResult;
import com.kratisai.controlplane.ingestion.research.ArchitecturePatternResult;
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
import com.kratisai.controlplane.planningagent.telemetry.TelemetryEvent;
import com.kratisai.controlplane.validation.ValidPasswordValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
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
    @DisplayName("registers constructor access for the password constraint validator")
    void registersPasswordConstraintValidator() {
        RuntimeHints hints = new RuntimeHints();
        AotHints aotHints = new AotHints();

        aotHints.registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(ValidPasswordValidator.class)
                        .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
    }

    @Test
    @DisplayName("registers WebSocket wire DTOs for Jackson binding")
    void registersWebSocketPayloadHints() {
        RuntimeHints hints = new RuntimeHints();
        AotHints aotHints = new AotHints();

        aotHints.registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(ClientPayload.AuthResult.class)
                        .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onMethod(ActivityKind.class, "fromString"))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TelemetryEvent.Thought.class)
                        .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
    }

    @Test
    @DisplayName("registers binding hints for LLM structured-output records")
    void registersRecordBindingHints() {
        RuntimeHints hints = new RuntimeHints();
        AotHints aotHints = new AotHints();

        aotHints.registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(DimensionDiscoveryResult.class)
                        .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onMethod(DimensionDiscoveryResult.class, "domains"))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(DimensionDiscoveryResult.DimensionResult.class)
                        .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(ArchitecturePatternResult.class)
                        .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
    }

    @Test
    @DisplayName("registers field and method access on the springdoc SpringWebProvider lazy proxy")
    void registersSpringWebProviderLazyProxyHints() {
        RuntimeHints hints = new RuntimeHints();
        AotHints aotHints = new AotHints();

        aotHints.registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TypeReference.of("org.springdoc.core.providers.SpringWebProvider"))
                        .withMemberCategory(MemberCategory.ACCESS_DECLARED_FIELDS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TypeReference.of("org.springdoc.core.providers.SpringWebProvider"))
                        .withMemberCategory(MemberCategory.INVOKE_DECLARED_METHODS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TypeReference.of("org.springdoc.core.providers.SpringWebProvider$$SpringCGLIB$$0"))
                        .withMemberCategory(MemberCategory.ACCESS_DECLARED_FIELDS))
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
