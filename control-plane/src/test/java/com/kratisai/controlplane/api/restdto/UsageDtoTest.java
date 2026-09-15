package com.kratisai.controlplane.api.restdto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.model.SandboxExecutionStatus;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

// The null-rejection test deliberately passes null to a record whose
// parameters are non-null, which SpotBugs flags as NP_NULL_PARAM_DEREF_NONVIRTUAL.
@SuppressFBWarnings("NP_NULL_PARAM_DEREF_NONVIRTUAL")
class UsageDtoTest {

    @Test
    void usageLogEntryDtoTest() {
        Instant now = Instant.now();
        UsageLogEntryDto dto = new UsageLogEntryDto(
                "id-1",
                now,
                10L,
                "Title",
                "Agent",
                SandboxExecutionStatus.COMPLETED,
                "user@example.com",
                Set.of("gpt-4o"),
                "EXECUTION",
                100L,
                0.05);
        assertThat(dto.id()).isEqualTo("id-1");
        assertThat(dto.timestamp()).isEqualTo(now);
        assertThat(dto.durationSeconds()).isEqualTo(10L);
        assertThat(dto.activityTitle()).isEqualTo("Title");
        assertThat(dto.agentName()).isEqualTo("Agent");
        assertThat(dto.status()).isEqualTo(SandboxExecutionStatus.COMPLETED);
        assertThat(dto.userEmail()).isEqualTo("user@example.com");
        assertThat(dto.modelIdentifiers()).containsExactly("gpt-4o");
        assertThat(dto.usageType()).isEqualTo("EXECUTION");
        assertThat(dto.totalTokens()).isEqualTo(100L);
        assertThat(dto.totalSpend()).isEqualTo(0.05);
    }

    @Test
    void usageLogEntryDto_sortsModelIdentifiersCaseInsensitively() {
        UsageLogEntryDto dto = new UsageLogEntryDto(
                "id-2",
                Instant.now(),
                null,
                "Ingestion",
                "Ingestion Worker",
                SandboxExecutionStatus.COMPLETED,
                "system@kratis.ai",
                Set.of("text-embedding-3-small", "gpt-4o"),
                "INGESTION",
                500L,
                0.01);
        assertThat(dto.modelIdentifiers()).containsExactly("gpt-4o", "text-embedding-3-small");
    }

    @Test
    void usageLogEntryDto_rejectsNullModelIdentifiers() {
        assertThatThrownBy(() -> new UsageLogEntryDto(
                        "id-3",
                        Instant.now(),
                        null,
                        "Ingestion",
                        "Ingestion Worker",
                        SandboxExecutionStatus.COMPLETED,
                        "system@kratis.ai",
                        null,
                        "INGESTION",
                        0L,
                        0.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void usageSummaryDtoTest() {
        Instant now = Instant.now();
        UsageSummaryDto.TimeSeriesPointDto point = new UsageSummaryDto.TimeSeriesPointDto(now, 0.01, 50L);
        UsageSummaryDto.ShareBreakdownDto share = new UsageSummaryDto.ShareBreakdownDto("gpt-4o", 0.01, 50L, 1L, 100.0);

        UsageSummaryDto summary =
                new UsageSummaryDto(0.01, 50L, 1L, List.of(point), List.of(share), List.of(share), List.of(share));

        assertThat(summary.totalCost()).isEqualTo(0.01);
        assertThat(summary.totalTokens()).isEqualTo(50L);
        assertThat(summary.totalOperations()).isEqualTo(1L);
        assertThat(summary.timeSeries()).hasSize(1);
        assertThat(summary.modelShare()).hasSize(1);
        assertThat(summary.agentShareByTime()).hasSize(1);
        assertThat(summary.agentShareByCost()).hasSize(1);
    }
}
