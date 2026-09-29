package com.kratisai.controlplane.ingestion;

import com.kratisai.controlplane.model.IngestionBatchLog;
import com.kratisai.controlplane.repository.IngestionBatchLogRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionBatchLogService {

    private final IngestionBatchLogRepository logRepository;
    private final JdbcTemplate jdbcTemplate;

    public IngestionBatchLogService(IngestionBatchLogRepository logRepository, JdbcTemplate jdbcTemplate) {
        this.logRepository = logRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void log(UUID batchId, UUID teamId, String level, String step, String message) {
        // Use jdbcTemplate instead of our repositories to avoid creating new sessions
        // whilst "work"
        // happens in a normal session.
        jdbcTemplate.update(
                "INSERT INTO ingestion_batch_logs (id, batch_id, team_id, level, step, message, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                batchId,
                teamId,
                level,
                step,
                message,
                java.sql.Timestamp.from(Instant.now()));
    }

    @Transactional(readOnly = true)
    public List<IngestionBatchLog> getLogsForBatch(UUID batchId) {
        return logRepository.findByBatchIdOrderByCreatedAtAsc(batchId);
    }

    public record BatchLogger(UUID batchId, UUID teamId, IngestionBatchLogService service) {
        public void info(String step, String message) {
            service.log(batchId, teamId, "INFO", step, message);
        }

        public void error(String step, String message) {
            service.log(batchId, teamId, "ERROR", step, message);
        }
    }
}
