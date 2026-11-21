package com.kratisai.controlplane.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class DatabaseCleanerTest {

    @Test
    void buildNonEmptyCheckSql_coversAllTablesWithMvccExistsProbe() {
        assertThat(DatabaseCleaner.buildNonEmptyCheckSql(List.of())).isEmpty();

        String sql = DatabaseCleaner.buildNonEmptyCheckSql(List.of("s1.users", "s1.teams"));
        assertThat(sql)
                .isEqualTo("SELECT 's1.users' AS table_name WHERE EXISTS (SELECT 1 FROM s1.users)"
                        + " UNION ALL SELECT 's1.teams' AS table_name WHERE EXISTS (SELECT 1 FROM s1.teams)");
    }

    @Test
    void cleanAll_skipsTruncateWhenNoTablesContainRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("test_schema")))
                .thenReturn(List.of("users", "teams", "databasechangelog"));
        String probeSql = DatabaseCleaner.buildNonEmptyCheckSql(List.of("test_schema.users", "test_schema.teams"));
        when(jdbc.queryForList(probeSql, String.class)).thenReturn(List.of());

        DatabaseCleaner cleaner = new DatabaseCleaner();
        ReflectionTestUtils.setField(cleaner, "schema", "test_schema");
        ReflectionTestUtils.setField(cleaner, "jdbcTemplate", jdbc);

        cleaner.cleanAll();

        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void cleanAll_truncatesOnlyNonEmptyTablesWithCascade() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("test_schema")))
                .thenReturn(List.of("users", "teams", "chats"));
        String probeSql = DatabaseCleaner.buildNonEmptyCheckSql(
                List.of("test_schema.users", "test_schema.teams", "test_schema.chats"));
        when(jdbc.queryForList(probeSql, String.class)).thenReturn(List.of("test_schema.teams"));

        DatabaseCleaner cleaner = new DatabaseCleaner();
        ReflectionTestUtils.setField(cleaner, "schema", "test_schema");
        ReflectionTestUtils.setField(cleaner, "jdbcTemplate", jdbc);

        cleaner.cleanAll();

        verify(jdbc).execute("SET lock_timeout = '1s'");
        verify(jdbc).execute("TRUNCATE TABLE test_schema.teams CASCADE");
        verify(jdbc).execute("SET lock_timeout = 0");
    }
}
