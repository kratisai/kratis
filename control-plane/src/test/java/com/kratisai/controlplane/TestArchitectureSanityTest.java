package com.kratisai.controlplane;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import com.kratisai.controlplane.testsupport.SlowTest;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
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
import org.springframework.test.context.DynamicPropertySource;

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
