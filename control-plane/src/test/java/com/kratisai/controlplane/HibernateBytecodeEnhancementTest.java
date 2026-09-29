package com.kratisai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import org.hibernate.engine.spi.PersistentAttributeInterceptable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

class HibernateBytecodeEnhancementTest {

    @Test
    void jpaEntitiesAreBytecodeEnhancedForNativeLazyLoading() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        assertThat(scanner.findCandidateComponents("com.kratisai.controlplane.model"))
                .isNotEmpty()
                .allSatisfy(HibernateBytecodeEnhancementTest::assertEntityIsEnhanced);
    }

    private static void assertEntityIsEnhanced(BeanDefinition definition) {
        String className = definition.getBeanClassName();
        try {
            Class<?> type = Class.forName(className);
            assertThat(PersistentAttributeInterceptable.class.isAssignableFrom(type))
                    .as(
                            "%s must be Hibernate bytecode-enhanced so GraalVM native images can lazy-load"
                                    + " associations without generating HibernateProxy subclasses"
                                    + " (BytecodeProvider is none)",
                            className)
                    .isTrue();
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Failed to load entity " + className, e);
        }
    }
}
