package com.kratisai.controlplane.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringIntegrationTest
@Transactional
class SurfaceSchemaMappingTest {
    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private Environment environment;

    @Test
    @SuppressFBWarnings("SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE")
    void legacyCrossRepoCallEdgesMigrationDeletesOnlyCrossRepoCalls() throws Exception {
        String schema = "legacy_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection pooled = dataSource.getConnection();
                Statement cleanup = pooled.createStatement()) {
            String pooledSchema = pooled.getSchema();
            assertThat(pooledSchema).isEqualTo(environment.getRequiredProperty("test.schema"));
            cleanup.execute("CREATE SCHEMA " + schema);
            try (Connection connection = DriverManager.getConnection(
                            environment.getRequiredProperty("spring.datasource.url"),
                            environment.getRequiredProperty("spring.datasource.username"),
                            environment.getRequiredProperty("spring.datasource.password"));
                    Statement statement = connection.createStatement()) {
                connection.setSchema(schema);
                statement.execute(
                        "CREATE TABLE ctx_edges (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), relation_type VARCHAR(50) NOT NULL)");
                statement.execute("INSERT INTO ctx_edges (relation_type) VALUES ('CROSS_REPO_CALL'), ('CALLS')");
                var database =
                        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
                database.setDefaultSchemaName(schema);
                database.setLiquibaseSchemaName(schema);
                try (var liquibase = new Liquibase(
                        "db/changelog/v0.1/022-clean-legacy-cross-repo-call-edges.yaml",
                        new ClassLoaderResourceAccessor(),
                        database)) {
                    liquibase.update(new Contexts(), new LabelExpression());
                    liquibase.update(new Contexts(), new LabelExpression());
                    try (var result = statement.executeQuery("SELECT relation_type FROM ctx_edges")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString(1)).isEqualTo("CALLS");
                        assertThat(result.next()).isFalse();
                    }
                }
                assertThat(pooled.getSchema()).isEqualTo(pooledSchema);
            } finally {
                cleanup.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void freshSchemaStoresIndependentSurfacesAndProvenance() {
        var context = testDataFactory.createDefaultContext("Surface schema", "surface-schema");
        entityManager.flush();
        UUID first = insertSurface(
                context.batch().getId(),
                context.repository().getId(),
                context.team().getId());
        UUID second = insertSurface(
                context.batch().getId(),
                context.repository().getId(),
                context.team().getId());
        assertThat(first).isNotEqualTo(second);
        jdbcTemplate.update(
                "INSERT INTO ctx_surface_attributes (surface_id, kind, value, detected_by) VALUES (?, 'TECHNOLOGY', 'unfamiliar', 'CMM'), (?, 'TECHNOLOGY', 'unfamiliar', 'MODEL')",
                first,
                first);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT detected_by FROM ctx_surface_attributes WHERE surface_id = ? ORDER BY detected_by",
                        String.class,
                        first))
                .containsExactly("CMM", "MODEL");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'ctx_surface_matches'",
                        Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'ctx_repo_synopses' AND column_name = 'citations' AND data_type = 'jsonb'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'ctx_surfaces' AND column_name IN ('scheme', 'shape', 'key', 'ceiling')",
                        String.class))
                .isEmpty();
    }

    private UUID insertSurface(UUID batchId, UUID repositoryId, UUID teamId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO ctx_surfaces (batch_id, repository_id, team_id, role, description, detected_by) VALUES (?, ?, ?, 'UNKNOWN', 'Unidentified interface', 'CMM') RETURNING id",
                UUID.class,
                batchId,
                repositoryId,
                teamId);
    }
}
