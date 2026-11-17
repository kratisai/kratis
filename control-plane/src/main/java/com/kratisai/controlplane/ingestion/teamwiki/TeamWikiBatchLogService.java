package com.kratisai.controlplane.ingestion.teamwiki;

import com.kratisai.controlplane.model.TeamWikiBatchLog;
import com.kratisai.controlplane.repository.TeamWikiBatchLogRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamWikiBatchLogService {

    static final int MAX_MESSAGE_CHARS = 8_000;
    private static final String TRUNCATED_SUFFIX = "... [truncated]";

    private final TeamWikiBatchLogRepository logRepository;
    private final JdbcTemplate jdbcTemplate;

    public TeamWikiBatchLogService(TeamWikiBatchLogRepository logRepository, JdbcTemplate jdbcTemplate) {
        this.logRepository = logRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void log(UUID teamWikiBatchId, UUID teamId, String level, String step, String message) {
        String bounded = boundMessage(message);
        jdbcTemplate.update(
                "INSERT INTO team_wiki_batch_logs (id, team_wiki_batch_id, team_id, level, step, message, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                teamWikiBatchId,
                teamId,
                level,
                step,
                bounded,
                java.sql.Timestamp.from(Instant.now()));
    }

    static String boundMessage(String message) {
        if (message == null) {
            return "";
        }
        if (message.length() <= MAX_MESSAGE_CHARS) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_CHARS - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX;
    }

    @Transactional(readOnly = true)
    public List<TeamWikiBatchLog> getLogsForBatch(UUID teamWikiBatchId) {
        return logRepository.findByTeamWikiBatchIdOrderByCreatedAtAsc(teamWikiBatchId);
    }

    public record BatchLogger(UUID batchId, UUID teamId, TeamWikiBatchLogService service) {
        public void debug(String step, String message) {
            service.log(batchId, teamId, "DEBUG", step, message);
        }

        public void info(String step, String message) {
            service.log(batchId, teamId, "INFO", step, message);
        }

        public void warn(String step, String message) {
            service.log(batchId, teamId, "WARN", step, message);
        }

        public void error(String step, String message) {
            service.log(batchId, teamId, "ERROR", step, message);
        }
    }
}
