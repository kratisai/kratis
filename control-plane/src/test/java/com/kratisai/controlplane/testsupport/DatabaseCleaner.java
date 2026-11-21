package com.kratisai.controlplane.testsupport;

import com.kratisai.controlplane.service.ClientSessionRegistry;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.testsupport.llm.TestLiteLLMClient;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;

@TestComponent
public class DatabaseCleaner {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseCleaner.class);

    @Value("${test.schema:public}")
    private String schema;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private EnvironmentSessionRegistry environmentSessionRegistry;

    @Autowired(required = false)
    private ClientSessionRegistry clientSessionRegistry;

    @Autowired(required = false)
    private TestLiteLLMClient testLiteLLMClient;

    private volatile List<String> cachedTablesToTruncate;
    private volatile String cachedNonEmptyCheckSql;

    private static final Set<String> EXCLUDED_TABLES = Set.of("databasechangelog", "databasechangeloglock");

    public void cleanAll() {
        long totalStart = System.nanoTime();
        long litellmNanos = 0L;
        long discoveryNanos = 0L;
        long truncateNanos = 0L;
        boolean lockTimeoutHealed = false;
        try {
            // Clear in-memory session registries that would otherwise leak across tests sharing the same context
            if (environmentSessionRegistry != null) {
                environmentSessionRegistry.clearAll();
            }
            if (clientSessionRegistry != null) {
                clientSessionRegistry.clearAll();
            }

            // Clean up LiteLLM models created during the test (best-effort, quick)
            long litellmStart = System.nanoTime();
            cleanupLiteLLMModels();
            litellmNanos = System.nanoTime() - litellmStart;

            if (cachedTablesToTruncate == null) {
                synchronized (this) {
                    if (cachedTablesToTruncate == null) {
                        long discStart = System.nanoTime();
                        List<String> tables = jdbcTemplate.queryForList(
                                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'",
                                String.class,
                                schema.toLowerCase());

                        List<String> resolved = tables.stream()
                                .map(String::toLowerCase)
                                .filter(tableName -> !EXCLUDED_TABLES.contains(tableName))
                                .map(tableName -> schema + "." + tableName)
                                .collect(Collectors.toList());
                        cachedNonEmptyCheckSql = buildNonEmptyCheckSql(resolved);
                        cachedTablesToTruncate = resolved;
                        discoveryNanos = System.nanoTime() - discStart;
                    }
                }
            }

            if (cachedTablesToTruncate.isEmpty()) {
                return;
            }

            long truncStart = System.nanoTime();
            List<String> nonEmptyTables = jdbcTemplate.queryForList(cachedNonEmptyCheckSql, String.class);
            if (nonEmptyTables.isEmpty()) {
                truncateNanos = System.nanoTime() - truncStart;
                return;
            }

            String truncateSql = "TRUNCATE TABLE " + String.join(", ", nonEmptyTables) + " CASCADE";

            try {
                jdbcTemplate.execute("SET lock_timeout = '1s'");
                jdbcTemplate.execute(truncateSql);
            } catch (Exception e) {
                lockTimeoutHealed = true;
                logger.warn(
                        "TRUNCATE blocked by lock. Self-healing by terminating conflicting sessions for schema {} and retrying...",
                        schema);
                terminateConnections();
                try {
                    jdbcTemplate.execute("SET lock_timeout = 0");
                    jdbcTemplate.execute(truncateSql);
                } catch (Exception ex) {
                    logger.error("TRUNCATE failed even after connection termination", ex);
                    throw ex;
                }
            } finally {
                try {
                    jdbcTemplate.execute("SET lock_timeout = 0");
                } catch (Exception e) {
                    // Ignore
                }
                truncateNanos = System.nanoTime() - truncStart;
            }
        } finally {
            TestTimingRegistry.instance()
                    .recordDbClean(
                            System.nanoTime() - totalStart,
                            litellmNanos,
                            discoveryNanos,
                            truncateNanos,
                            lockTimeoutHealed);
        }
    }

    /**
     * Builds a single MVCC-exact probe query returning only tables that currently contain at least
     * one row, avoiding unnecessary {@code ACCESS EXCLUSIVE} locks on empty tables.
     */
    static String buildNonEmptyCheckSql(List<String> qualifiedTables) {
        if (qualifiedTables == null || qualifiedTables.isEmpty()) {
            return "";
        }
        return qualifiedTables.stream()
                .map(table -> "SELECT '" + table + "' AS table_name WHERE EXISTS (SELECT 1 FROM " + table + ")")
                .collect(Collectors.joining(" UNION ALL "));
    }

    private void terminateConnections() {
        if (schema != null && !"public".equalsIgnoreCase(schema)) {
            try {
                jdbcTemplate.queryForList(
                        "SELECT pg_terminate_backend(pid) FROM pg_locks l "
                                + "JOIN pg_class c ON l.relation = c.oid "
                                + "JOIN pg_namespace n ON c.relnamespace = n.oid "
                                + "WHERE n.nspname = ? AND pid != pg_backend_pid()",
                        Boolean.class,
                        schema.toLowerCase());
            } catch (Exception e) {
                // ignore if permission is lacking
            }
        }
    }

    private void cleanupLiteLLMModels() {
        if (testLiteLLMClient == null) {
            return;
        }

        try {
            testLiteLLMClient.cleanupTrackedModels();
        } catch (Exception e) {
            logger.warn("Failed to cleanup LiteLLM models: {}", e.getMessage());
        }
    }
}
