package com.kratisai.controlplane;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import com.kratisai.controlplane.testsupport.ContextCacheTracker;
import com.kratisai.controlplane.testsupport.IngestionTestStubs;
import com.kratisai.controlplane.testsupport.SlowTest;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.UseRealNativeBinaries;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class TestArchitectureSanityTest {

    private static JavaClasses testClasses;

    @BeforeAll
    static void importTestClasses() {
        testClasses = new ClassFileImporter()
                .withImportOption(new OnlyIncludeTestsOption())
                .withImportOption(new ImportOption.DoNotIncludeJars())
                .importPackages("com.kratisai.controlplane");
    }

    public static class OnlyIncludeTestsOption implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return location.contains("/test-classes/");
        }
    }

    static final ArchRule INTEGRATION_TESTS_MUST_USE_SPRING_INTEGRATION_TEST =
            classes().that().haveSimpleNameEndingWith("IntegrationTest").should(haveValidIntegrationTestAnnotation());

    static final ArchRule NO_DIRECT_SPRING_BOOT_TEST = noClasses()
            .that()
            .areNotAnnotations()
            .should()
            .beAnnotatedWith(SpringBootTest.class)
            .because(
                    "Tests should use @SpringIntegrationTest to share context and avoid spinning up bespoke ApplicationContexts.");

    static final ArchRule NO_DYNAMIC_PROPERTY_SOURCE = noMethods()
            .should()
            .beAnnotatedWith(DynamicPropertySource.class)
            .because("DynamicPropertySource causes context pollution and prevents Spring context cache reuse. "
                    + "Use system properties in PostgresTestInitializer.java or configuration files instead.");

    static final ArchRule NO_CONTEXT_FORKING_ANNOTATIONS_ON_SPRING_INTEGRATION_TESTS = classes()
            .that()
            .areNotAnnotations()
            .should(notForkSpringIntegrationContext())
            .because(
                    "Per-class @MockitoBean/@MockitoSpyBean/@TestPropertySource/@DirtiesContext/@Import forks a new "
                            + "Spring context inside the slot, runs Liquibase again, and stalls sibling workers on Spring's "
                            + "contextCache lock. Put shared spies in TestSpiesConfig and mutate properties in @BeforeEach/@AfterEach.");

    static final ArchRule TEST_LISTENERS_MUST_USE_LOCK_FREE_CONTEXT_LOOKUP = classes()
            .that()
            .resideInAPackage("..testsupport..")
            .and()
            .implement(TestExecutionListener.class)
            .and()
            .doNotHaveFullyQualifiedName(ContextCacheTracker.class.getName())
            .should(useLockFreeContextLookup())
            .because(
                    "Calling TestContext.hasApplicationContext()/getApplicationContext() directly in listeners "
                            + "acquires Spring's global contextCache monitor lock and freezes unrelated slots during context boot.");

    static final ArchRule REAL_BINARY_TESTS_MUST_NOT_USE_INGESTION_STUBS = classes()
            .that()
            .areNotAnnotations()
            .should(notStubRealBinaryTests())
            .because("@UseRealNativeBinaries tests exist specifically to validate real native binaries and must not "
                    + "stub them via IngestionTestStubs.");

    @Test
    void integrationTestsMustUseSpringIntegrationTest() {
        INTEGRATION_TESTS_MUST_USE_SPRING_INTEGRATION_TEST.check(testClasses);
    }

    @Test
    void noDirectSpringBootTest() {
        NO_DIRECT_SPRING_BOOT_TEST.check(testClasses);
    }

    @Test
    void noDynamicPropertySource() {
        NO_DYNAMIC_PROPERTY_SOURCE.check(testClasses);
    }

    @Test
    void noContextForkingAnnotationsOnSpringIntegrationTests() {
        NO_CONTEXT_FORKING_ANNOTATIONS_ON_SPRING_INTEGRATION_TESTS.check(testClasses);
    }

    @Test
    void testListenersMustUseLockFreeContextLookup() {
        TEST_LISTENERS_MUST_USE_LOCK_FREE_CONTEXT_LOOKUP.check(testClasses);
    }

    @Test
    void realBinaryTestsMustNotUseIngestionStubs() {
        REAL_BINARY_TESTS_MUST_NOT_USE_INGESTION_STUBS.check(testClasses);
    }

    private static ArchCondition<JavaClass> notStubRealBinaryTests() {
        return new ArchCondition<>("not depend on IngestionTestStubs when annotated with @UseRealNativeBinaries") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                boolean hasRealBinaryAnnotation = javaClass.getClassHierarchy().stream()
                        .anyMatch(c -> c.isAnnotatedWith(UseRealNativeBinaries.class));
                if (!hasRealBinaryAnnotation) {
                    return;
                }
                boolean usesStubs = javaClass.getDirectDependenciesFromSelf().stream()
                        .anyMatch(dep -> dep.getTargetClass().isEquivalentTo(IngestionTestStubs.class));
                if (usesStubs) {
                    events.add(
                            SimpleConditionEvent.violated(
                                    javaClass,
                                    javaClass.getName()
                                            + " is marked @UseRealNativeBinaries and must not stub binaries with IngestionTestStubs"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notForkSpringIntegrationContext() {
        return new ArchCondition<>("not declare context-forking annotations on @SpringIntegrationTest classes") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                boolean hasSpringIntegration = javaClass.getClassHierarchy().stream()
                        .anyMatch(c -> c.isAnnotatedWith(SpringIntegrationTest.class));
                if (!hasSpringIntegration) {
                    return;
                }
                if (javaClass.isAnnotatedWith(TestPropertySource.class)) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass, javaClass.getName() + " must not declare @TestPropertySource"));
                }
                if (javaClass.isAnnotatedWith(DirtiesContext.class)) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass, javaClass.getName() + " must not declare @DirtiesContext"));
                }
                if (javaClass.isAnnotatedWith(Import.class)) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass, javaClass.getName() + " must not declare per-class @Import"));
                }
                for (JavaField field : javaClass.getFields()) {
                    if (field.isAnnotatedWith(MockitoBean.class) || field.isAnnotatedWith(MockitoSpyBean.class)) {
                        events.add(
                                SimpleConditionEvent.violated(
                                        field,
                                        javaClass.getName()
                                                + "."
                                                + field.getName()
                                                + " must not use @MockitoBean/@MockitoSpyBean; add a shared spy in TestSpiesConfig"));
                    }
                }
                for (JavaMethod method : javaClass.getMethods()) {
                    if (method.isAnnotatedWith(DirtiesContext.class)) {
                        events.add(SimpleConditionEvent.violated(
                                method,
                                javaClass.getName() + "#" + method.getName() + " must not declare @DirtiesContext"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> useLockFreeContextLookup() {
        return new ArchCondition<>("resolve ApplicationContext via ContextCacheTracker.resolvedContext") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                javaClass.getMethodCallsFromSelf().stream()
                        .filter(call -> call.getTarget().getOwner().isEquivalentTo(TestContext.class))
                        .filter(call ->
                                "hasApplicationContext".equals(call.getTarget().getName())
                                        || "getApplicationContext"
                                                .equals(call.getTarget().getName()))
                        .forEach(call -> events.add(SimpleConditionEvent.violated(
                                javaClass,
                                javaClass.getName()
                                        + " calls TestContext."
                                        + call.getTarget().getName()
                                        + "(); use ContextCacheTracker.resolvedContext(testContext) instead")));
            }
        };
    }

    private static ArchCondition<JavaClass> haveValidIntegrationTestAnnotation() {
        return new ArchCondition<>("be annotated with @SpringIntegrationTest or @SlowTest") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                boolean isAbstract = javaClass.getModifiers().contains(JavaModifier.ABSTRACT);
                boolean isInterface = javaClass.isInterface();

                if (!isInterface && !isAbstract) {
                    boolean hasSpringIntegrationTest = javaClass.getClassHierarchy().stream()
                            .anyMatch(c -> c.isAnnotatedWith(SpringIntegrationTest.class));
                    boolean hasSlowTest =
                            javaClass.getClassHierarchy().stream().anyMatch(c -> c.isAnnotatedWith(SlowTest.class));

                    if (!hasSpringIntegrationTest && !hasSlowTest) {
                        String message = String.format(
                                "Class %s does not declare @SpringIntegrationTest or @SlowTest", javaClass.getName());
                        events.add(SimpleConditionEvent.violated(javaClass, message));
                    }
                }
            }
        };
    }
}
