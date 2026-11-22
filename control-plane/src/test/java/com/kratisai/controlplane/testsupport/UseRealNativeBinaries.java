package com.kratisai.controlplane.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test class (or method) as validating real native binaries ({@code codebase-memory-mcp},
 * {@code syft}, {@code scc}, {@code kratis-connector}, or Docker sandboxes) rather than test stubs.
 *
 * <p>{@link TestTimingRegistry} permits real parser/SBOM subprocess execution only in tests marked
 * with this annotation, and {@code TestArchitectureSanityTest} prevents stubbing them with
 * {@code IngestionTestStubs}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface UseRealNativeBinaries {}
