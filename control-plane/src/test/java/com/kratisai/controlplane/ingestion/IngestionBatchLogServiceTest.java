package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.kratisai.controlplane.repository.IngestionBatchLogRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class IngestionBatchLogServiceTest {

    @Test
    void debugWritesDebugLevelAndCapsOversizedMessages() {
        IngestionBatchLogRepository repository = mock(IngestionBatchLogRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        IngestionBatchLogService service = new IngestionBatchLogService(repository, jdbcTemplate);
        UUID batchId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        IngestionBatchLogService.BatchLogger batchLogger =
                new IngestionBatchLogService.BatchLogger(batchId, teamId, service);

        String oversized = "x".repeat(IngestionBatchLogService.MAX_MESSAGE_CHARS + 500);
        batchLogger.debug("EXTRACT_SURFACES", oversized);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate)
                .update(
                        anyString(),
                        any(UUID.class),
                        eq(batchId),
                        eq(teamId),
                        eq("DEBUG"),
                        eq("EXTRACT_SURFACES"),
                        messageCaptor.capture(),
                        any());
        String stored = messageCaptor.getValue();
        assertThat(stored).hasSize(IngestionBatchLogService.MAX_MESSAGE_CHARS);
        assertThat(stored).endsWith("... [truncated]");
        assertThat(IngestionBatchLogService.boundMessage(null)).isEmpty();
        assertThat(IngestionBatchLogService.boundMessage("short")).isEqualTo("short");
    }
}
