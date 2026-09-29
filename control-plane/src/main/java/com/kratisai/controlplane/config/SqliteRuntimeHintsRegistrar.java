package com.kratisai.controlplane.config;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteDataSource;
import org.sqlite.SQLiteJDBCLoader;

/**
 * Runtime hints for SQLite JDBC driver to support GraalVM native image compilation.
 * SQLite JDBC uses native code and reflection that must be registered for AOT.
 */
@Configuration
@ImportRuntimeHints(SqliteRuntimeHintsRegistrar.class)
public class SqliteRuntimeHintsRegistrar implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // Register SQLite JDBC driver for reflection
        hints.reflection()
                .registerType(JDBC.class)
                .registerType(SQLiteConfig.class)
                .registerType(SQLiteConnection.class)
                .registerType(SQLiteDataSource.class)
                .registerType(SQLiteJDBCLoader.class);

        // Register native library loading
        hints.resources().registerPattern("org/sqlite/native/**");
    }
}
