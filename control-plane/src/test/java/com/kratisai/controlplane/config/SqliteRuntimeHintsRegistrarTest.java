package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteDataSource;
import org.sqlite.SQLiteJDBCLoader;

@DisplayName("SqliteRuntimeHintsRegistrar")
class SqliteRuntimeHintsRegistrarTest {

    @Test
    @DisplayName("registers reflection hints for SQLite driver classes")
    void registersReflectionHints() {
        RuntimeHints hints = new RuntimeHints();
        SqliteRuntimeHintsRegistrar registrar = new SqliteRuntimeHintsRegistrar();

        registrar.registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection().onType(JDBC.class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(SQLiteConfig.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(SQLiteConnection.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(SQLiteDataSource.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(SQLiteJDBCLoader.class))
                .accepts(hints);
    }

    @Test
    @DisplayName("registers resource pattern for SQLite native libraries")
    void registersResourceHints() {
        RuntimeHints hints = new RuntimeHints();
        SqliteRuntimeHintsRegistrar registrar = new SqliteRuntimeHintsRegistrar();

        registrar.registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.resource().forResource("org/sqlite/native/Linux/x86_64/libsqlitejdbc.so"))
                .accepts(hints);
    }
}
