package com.kratisai.controlplane;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import jakarta.persistence.Entity;
import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Deterministic dead-code detection. Every main class must be reachable from a production entry
 * point: Spring instantiates component stereotypes reflectively, Hibernate instantiates entities
 * reflectively, and META-INF/spring/aot.factories loads registrars by class name — those are the
 * only ways a class enters production use in this application (no hand-rolled reflection, no
 * META-INF/services, no Liquibase custom changesets). Classes unreachable from those roots can
 * only be reached from test code, which DoNotIncludeTests removes from the graph, and fail here.
 */
class ProductionReachabilityTest {

    private static final String ROOT_PACKAGE = "com.kratisai.controlplane";

    /**
     * Compiler artifacts (anonymous classes, synthetic enum-switch helpers) execute as part of
     * their enclosing class. ArchUnit records no incoming dependency for them, so reachability is
     * attributed to the enclosing class and they are never reported as dead on their own.
     */
    private static final Pattern COMPILER_ARTIFACT = Pattern.compile(".+\\$\\d+$");

    private static final Set<String> AOT_FACTORY_REGISTRARS = Set.of(
            "com.kratisai.controlplane.config.AotHints",
            "com.kratisai.controlplane.config.LiquibaseRuntimeHintsRegistrar",
            "com.kratisai.controlplane.config.SqliteRuntimeHintsRegistrar");

    private static final List<Class<? extends Annotation>> ENTRY_POINT_ANNOTATIONS = List.of(
            SpringBootApplication.class,
            Configuration.class,
            Component.class,
            Service.class,
            Repository.class,
            Controller.class,
            RestController.class,
            ControllerAdvice.class,
            RestControllerAdvice.class,
            ConfigurationProperties.class,
            Entity.class);

    @Test
    void everyProductionClassIsReachableFromAnEntryPoint() {
        Map<String, JavaClass> classes = importProductionClasses();
        assertAotRegistrarsExist(classes);

        Map<String, List<JavaClass>> compilerArtifacts = collectCompilerArtifacts(classes);
        Map<String, Set<JavaClass>> annotationValueEdges = collectAnnotationValueEdges(classes);
        Set<JavaClass> reachable =
                reachableFrom(classes, entryPointRoots(classes), compilerArtifacts, annotationValueEdges);

        Set<String> unreachable = new TreeSet<>();
        for (JavaClass clazz : classes.values()) {
            if (!reachable.contains(clazz) && !isCompilerArtifact(clazz) && !isConstantHolder(clazz)) {
                unreachable.add(clazz.getName());
            }
        }

        assertTrue(
                unreachable.isEmpty(),
                "Unreachable from every production entry point, so only tests can use it:\n"
                        + unreachable.stream().map(name -> "  " + name + "\n").collect(Collectors.joining())
                        + "Delete the class, wire it into production, or — if it is a framework-installed\n"
                        + "entry point this graph does not model — extend the root list in this test.");
    }

    private Map<String, JavaClass> importProductionClasses() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(new ImportOption.DoNotIncludeJars())
                .withImportOption(NotAotGenerated.INSTANCE)
                .importPackages(ROOT_PACKAGE);
        Map<String, JavaClass> byName = new HashMap<>();
        for (JavaClass clazz : classes) {
            byName.put(clazz.getName(), clazz);
        }
        return byName;
    }

    private void assertAotRegistrarsExist(Map<String, JavaClass> classes) {
        Set<String> missing = new TreeSet<>(AOT_FACTORY_REGISTRARS);
        missing.removeAll(classes.keySet());
        assertTrue(
                missing.isEmpty(),
                "aot.factories registrars no longer exist or are excluded from the import: " + missing
                        + "; update AOT_FACTORY_REGISTRARS");
    }

    private static Set<JavaClass> entryPointRoots(Map<String, JavaClass> classes) {
        Set<JavaClass> roots = new HashSet<>();
        for (JavaClass clazz : classes.values()) {
            if (AOT_FACTORY_REGISTRARS.contains(clazz.getName()) || hasEntryPointAnnotation(clazz)) {
                roots.add(clazz);
            }
        }
        return roots;
    }

    private static boolean hasEntryPointAnnotation(JavaClass clazz) {
        for (Class<? extends Annotation> annotation : ENTRY_POINT_ANNOTATIONS) {
            if (clazz.isAnnotatedWith(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, List<JavaClass>> collectCompilerArtifacts(Map<String, JavaClass> classes) {
        Map<String, List<JavaClass>> byEnclosing = new HashMap<>();
        for (JavaClass clazz : classes.values()) {
            if (isCompilerArtifact(clazz)) {
                clazz.getEnclosingClass().map(JavaClass::getName).ifPresent(enclosing -> byEnclosing
                        .computeIfAbsent(enclosing, key -> new ArrayList<>())
                        .add(clazz));
            }
        }
        return byEnclosing;
    }

    private static boolean isCompilerArtifact(JavaClass clazz) {
        return clazz.getSimpleName().isEmpty()
                || COMPILER_ARTIFACT.matcher(clazz.getName()).matches();
    }

    /**
     * Class references inside annotation arguments (@Convert(converter = ...), @Import, @ImportRuntimeHints)
     * are reflective entry points invisible to the bytecode dependency graph.
     */
    private static Map<String, Set<JavaClass>> collectAnnotationValueEdges(Map<String, JavaClass> classes) {
        Map<String, Set<JavaClass>> edges = new HashMap<>();
        for (JavaClass clazz : classes.values()) {
            Set<JavaClass> targets = edges.computeIfAbsent(clazz.getName(), key -> new HashSet<>());
            collectAnnotationValues(clazz.getAnnotations(), targets);
            for (JavaField field : clazz.getFields()) {
                collectAnnotationValues(field.getAnnotations(), targets);
            }
            for (JavaCodeUnit codeUnit : annotationBearingCodeUnits(clazz)) {
                collectAnnotationValues(codeUnit.getAnnotations(), targets);
                codeUnit.getParameters()
                        .forEach(parameter -> collectAnnotationValues(parameter.getAnnotations(), targets));
            }
        }
        return edges;
    }

    private static List<? extends JavaCodeUnit> annotationBearingCodeUnits(JavaClass clazz) {
        List<JavaCodeUnit> codeUnits = new ArrayList<>(clazz.getMethods());
        codeUnits.addAll(clazz.getConstructors());
        return codeUnits;
    }

    private static void collectAnnotationValues(Set<? extends JavaAnnotation<?>> annotations, Set<JavaClass> targets) {
        for (JavaAnnotation<?> annotation : annotations) {
            for (Object value : annotation.getProperties().values()) {
                collectAnnotationValue(value, targets);
            }
        }
    }

    private static void collectAnnotationValue(Object value, Set<JavaClass> targets) {
        if (value instanceof JavaClass javaClass) {
            targets.add(javaClass);
        } else if (value instanceof JavaAnnotation<?> nested) {
            collectAnnotationValues(Set.of(nested), targets);
        } else if (value instanceof Object[] array) {
            for (Object element : array) {
                collectAnnotationValue(element, targets);
            }
        }
    }

    private static Set<JavaClass> reachableFrom(
            Map<String, JavaClass> classes,
            Set<JavaClass> roots,
            Map<String, List<JavaClass>> compilerArtifacts,
            Map<String, Set<JavaClass>> annotationValueEdges) {
        Set<JavaClass> visited = new HashSet<>();
        Deque<JavaClass> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            JavaClass current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            for (Dependency dependency : current.getDirectDependenciesFromSelf()) {
                enqueue(classes, visited, queue, dependency.getTargetClass());
            }
            for (JavaClass target : annotationValueEdges.getOrDefault(current.getName(), Set.of())) {
                enqueue(classes, visited, queue, target);
            }
            for (JavaClass artifact : compilerArtifacts.getOrDefault(current.getName(), List.of())) {
                enqueue(classes, visited, queue, artifact);
            }
            // Using a nested class keeps its container file alive (type namespaces like LiteLLMDto).
            current.getEnclosingClass().ifPresent(enclosing -> enqueue(classes, visited, queue, enclosing));
        }
        return visited;
    }

    private static void enqueue(
            Map<String, JavaClass> classes, Set<JavaClass> visited, Deque<JavaClass> queue, JavaClass candidate) {
        JavaClass target = classes.get(candidate.getName());
        if (target != null && !visited.contains(target)) {
            queue.add(target);
        }
    }

    /**
     * Classes whose entire content is compile-time constants are inlined by javac and leave no
     * bytecode reference, so the graph cannot track them; they are exempt rather than frozen.
     */
    private static boolean isConstantHolder(JavaClass clazz) {
        boolean hasInlinedField = false;
        for (JavaField field : clazz.getFields()) {
            JavaClass fieldType = field.getRawType();
            boolean inlinable = fieldType.isPrimitive() || fieldType.isEquivalentTo(String.class);
            if (!inlinable
                    || !field.getModifiers().contains(JavaModifier.FINAL)
                    || !field.getModifiers().contains(JavaModifier.STATIC)) {
                return false;
            }
            hasInlinedField = true;
        }
        return hasInlinedField && clazz.getMethods().isEmpty();
    }

    private enum NotAotGenerated implements ImportOption {
        INSTANCE;

        @Override
        public boolean includes(Location location) {
            return !location.contains("__BeanDefinitions")
                    && !location.contains("__BeanFactoryRegistrations")
                    && !location.contains("__AotProcessor");
        }
    }
}
