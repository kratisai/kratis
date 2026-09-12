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
import com.kratisai.controlplane.planningagent.telemetry.TelemetryEvent;
import com.kratisai.controlplane.validation.ValidPasswordValidator;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.util.ClassUtils;

public class AotHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.proxies().registerJdkProxy(ModelDiscoveryClient.class);

        registerWebSocketPayloadHints(hints, classLoader);
        registerRecordBindingHints(hints, classLoader);

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

        // Hibernate Validator instantiates constraint validators reflectively through Spring's
        // ConstraintValidatorFactory. ValidPasswordValidator is only referenced from the
        // @Constraint(validatedBy = ...) annotation, so GraalVM static analysis never sees its
        // constructor and strips it; the first request that triggers @ValidPassword then fails
        // with "No default constructor found". Register the constructor so it can be created.
        hints.reflection().registerType(ValidPasswordValidator.class, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);

        // springdoc's SwaggerConfig.swaggerWelcome(...) takes a @Lazy SpringWebProvider, so
        // Spring AOT pre-generates a CGLIB subclass but does not register the full reflection
        // surface the proxy needs. CGLIB writes the proxy's CGLIB$FACTORY_DATA field
        // reflectively on first use, and CglibAopProxy delegates to the target with
        // Method.invoke on the declared SpringWebProvider methods (findPathPrefix and
        // friends). Without both hints the native binary starts but every /swagger-ui and
        // /api-docs request fails with MissingReflectionRegistrationError.
        hints.reflection()
                .registerType(
                        TypeReference.of("org.springdoc.core.providers.SpringWebProvider"),
                        MemberCategory.ACCESS_DECLARED_FIELDS,
                        MemberCategory.INVOKE_DECLARED_METHODS);
        hints.reflection()
                .registerType(
                        TypeReference.of("org.springdoc.core.providers.SpringWebProvider$$SpringCGLIB$$0"),
                        MemberCategory.ACCESS_DECLARED_FIELDS,
                        MemberCategory.INVOKE_DECLARED_METHODS);

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

        // Liquibase changelogs are classpath resources loaded at runtime; GraalVM strips
        // unregistered resources, failing startup with "no changelog could be found".
        hints.resources().registerPattern("db/changelog-master.yaml");
        hints.resources().registerPattern("db/changelog/.*");

        // Register entity ID array types for Hibernate MultiIdEntityLoaderArrayParam
        hints.reflection().registerType(java.util.UUID[].class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(Long[].class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
    }

    /**
     * The WebSocket JSON-RPC layer resolves payload classes at runtime from the payload-type enums
     * and hands them to Jackson's {@code ObjectMapper.treeToValue}, an indirection the AOT engine
     * cannot see. Without reflection hints for these records and their nested enums, the first
     * connector or browser message fails with {@code MissingReflectionRegistrationError}.
     *
     * <p>Scanning the wire-DTO package keeps new payloads covered automatically; Spring uses the
     * same binding hints for controller models.
     */
    private void registerWebSocketPayloadHints(RuntimeHints hints, ClassLoader classLoader) {
        BindingReflectionHintsRegistrar registrar = new BindingReflectionHintsRegistrar();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);
        List<Type> payloadTypes = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.kratisai.controlplane.api.wsdto")) {
            payloadTypes.add(ClassUtils.resolveClassName(candidate.getBeanClassName(), classLoader));
        }
        registrar.registerReflectionHints(hints.reflection(), payloadTypes.toArray(new Type[0]));

        // TelemetryEvent is a sealed hierarchy from another package embedded in
        // ClientPayload.TelemetryResult, so the wire-DTO scan does not reach its records.
        registerBindingHierarchy(hints, registrar, TelemetryEvent.class);
    }

    /**
     * LLM structured-output types are deserialized at runtime by Jackson through Spring AI's
     * {@code BeanOutputConverter} and {@code ReActLoop.structured}, an indirection the AOT engine
     * cannot see. Record classes need their record-component accessors registered or Jackson
     * fails in the native image with {@code UnsupportedFeatureError: Record components not available}.
     *
     * <p>Scanning the application's own base package keeps every current and future record covered
     * automatically, including nested records such as {@code DimensionDiscoveryResult.DimensionResult}.
     */
    private void registerRecordBindingHints(RuntimeHints hints, ClassLoader classLoader) {
        BindingReflectionHintsRegistrar registrar = new BindingReflectionHintsRegistrar();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);
        Set<Class<?>> recordTypes = new LinkedHashSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.kratisai.controlplane")) {
            Class<?> type = ClassUtils.resolveClassName(candidate.getBeanClassName(), classLoader);
            if (type.isRecord()) {
                recordTypes.add(type);
            }
        }
        registrar.registerReflectionHints(hints.reflection(), recordTypes.toArray(new Type[0]));
    }

    private void registerBindingHierarchy(
            RuntimeHints hints, BindingReflectionHintsRegistrar registrar, Class<?> root) {
        Set<Class<?>> types = new LinkedHashSet<>();
        collectHierarchy(root, types);
        registrar.registerReflectionHints(hints.reflection(), types.toArray(new Type[0]));
    }

    private static void collectHierarchy(Class<?> type, Set<Class<?>> types) {
        if (!types.add(type)) {
            return;
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            collectHierarchy(nested, types);
        }
        if (type.isSealed()) {
            for (Class<?> permitted : type.getPermittedSubclasses()) {
                collectHierarchy(permitted, types);
            }
        }
    }
}
