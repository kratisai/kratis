package com.kratisai.controlplane.config;

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
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

public class AotHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.proxies().registerJdkProxy(ModelDiscoveryClient.class);

        hints.reflection()
                .registerType(
                        DependenciesResponse.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS);

        hints.reflection()
                .registerType(
                        RepositorySummary.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS);

        hints.reflection()
                .registerType(
                        DimensionOverview.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS);

        hints.reflection()
                .registerType(
                        DimensionDetail.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS);

        hints.reflection()
                .registerType(
                        RankedFile.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS);

        hints.reflection()
                .registerType(
                        PatternSummary.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS);

        hints.reflection()
                .registerType(
                        WikiPageSummary.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS);

        hints.reflection()
                .registerType(
                        WikiSearchResponse.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS);

        hints.reflection()
                .registerType(
                        WikiSearchResult.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS);

        hints.reflection()
                .registerType(
                        AgentThinking.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS);

        // OkHttp 4.x is pulled in transitively by spring-ai-anthropic, spring-ai-openai,
        // and spring-ai-google-genai. OkHttp is written in Kotlin; its companion objects are
        // synthetic inner classes that GraalVM static analysis does not discover automatically,
        // causing "image heap writing found a class not seen during static analysis" failures
        // at Phase 8 of the native build.
        //
        // RetryAndFollowUpInterceptor$Companion is the confirmed failure from the build log.
        // The --initialize-at-run-time flag in the native-maven-plugin (pom.xml) is the
        // primary fix; this reflection registration is belt-and-suspenders coverage.
        hints.reflection()
                .registerType(
                        TypeReference.of("okhttp3.internal.http.RetryAndFollowUpInterceptor$Companion"),
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS,
                        MemberCategory.ACCESS_DECLARED_FIELDS);

        // OkHttp loads its public-suffix list from a classpath resource at runtime.
        // Without this hint the resource is stripped from the native image and any
        // code that calls HttpUrl.topPrivateDomain() / cookie handling will fail.
        hints.resources().registerPattern("okhttp3/internal/publicsuffix/PublicSuffixDatabase.gz");
    }
}
