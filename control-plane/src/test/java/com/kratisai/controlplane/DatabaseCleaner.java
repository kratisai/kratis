package com.kratisai.controlplane;

import com.kratisai.controlplane.service.ClientSessionRegistry;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
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

    private static final Set<String> EXCLUDED_TABLES = Set.of("databasechangelog", "databasechangeloglock");

    public void cleanAll() {
        // Clear in-memory session registries that would otherwise leak across tests sharing the same context
        if (environmentSessionRegistry != null) {
            environmentSessionRegistry.clearAll();
        }
        if (clientSessionRegistry != null) {
            clientSessionRegistry.clearAll();
        }

        // Clean up LiteLLM models created during the test (best-effort, quick)
        cleanupLiteLLMModels();

        if (cachedTablesToTruncate == null) {
            synchronized (this) {
                if (cachedTablesToTruncate == null) {
                    List<String> tables = jdbcTemplate.queryForList(
                            "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'",
                            String.class,
                            schema.toLowerCase());

                    cachedTablesToTruncate = tables.stream()
                            .map(String::toLowerCase)
                            .filter(tableName -> !EXCLUDED_TABLES.contains(tableName))
                            .map(tableName -> schema + "." + tableName)
                            .collect(Collectors.toList());
                }
            }
        }

        if (cachedTablesToTruncate.isEmpty()) {
            return;
        }

        String truncateSql = "TRUNCATE TABLE " + String.join(", ", cachedTablesToTruncate) + " CASCADE";

        try {
            jdbcTemplate.execute("SET lock_timeout = '1s'");
            jdbcTemplate.execute(truncateSql);
        } catch (Exception e) {
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
        }
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
