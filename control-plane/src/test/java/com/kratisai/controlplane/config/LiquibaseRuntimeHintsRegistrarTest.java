package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;

import liquibase.change.AbstractChange;
import liquibase.change.AbstractSQLChange;
import liquibase.change.ColumnConfig;
import liquibase.change.ConstraintsConfig;
import liquibase.change.core.AddForeignKeyConstraintChange;
import liquibase.change.core.AddUniqueConstraintChange;
import liquibase.change.core.CreateIndexChange;
import liquibase.change.core.CreateTableChange;
import liquibase.change.core.RawSQLChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

@DisplayName("LiquibaseRuntimeHintsRegistrar")
class LiquibaseRuntimeHintsRegistrarTest {

    @Test
    @DisplayName("registers reflection hints on AbstractSQLChange including public method invocation")
    void registersReflectionHintsOnAbstractSqlChange() {
        RuntimeHints hints = new RuntimeHints();
        LiquibaseRuntimeHintsRegistrar registrar = new LiquibaseRuntimeHintsRegistrar();

        registrar.registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(AbstractSQLChange.class)
                        .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(AbstractSQLChange.class)
                        .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS))
                .accepts(hints);
    }

    @Test
    @DisplayName("registers reflection hints for baseline migration change types and configs")
    void registersReflectionHintsForBaselineChanges() {
        RuntimeHints hints = new RuntimeHints();
        LiquibaseRuntimeHintsRegistrar registrar = new LiquibaseRuntimeHintsRegistrar();

        registrar.registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection().onType(AbstractChange.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(RawSQLChange.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(CreateTableChange.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(ColumnConfig.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(ConstraintsConfig.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(CreateIndexChange.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(AddForeignKeyConstraintChange.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(AddUniqueConstraintChange.class))
                .accepts(hints);
    }

    @Test
    @DisplayName("registers resource hints for changelog master and changelogs directory")
    void registersResourceHints() {
        RuntimeHints hints = new RuntimeHints();
        LiquibaseRuntimeHintsRegistrar registrar = new LiquibaseRuntimeHintsRegistrar();

        registrar.registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.resource().forResource("db/changelog-master.yaml"))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forResource("db/changelog/.*"))
                .accepts(hints);
    }
}
