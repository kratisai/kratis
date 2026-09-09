package com.kratisai.controlplane.api.restdto;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.model.SandboxExecutionStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

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
                "gpt-4o",
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
        assertThat(dto.modelIdentifier()).isEqualTo("gpt-4o");
        assertThat(dto.usageType()).isEqualTo("EXECUTION");
        assertThat(dto.totalTokens()).isEqualTo(100L);
        assertThat(dto.totalSpend()).isEqualTo(0.05);
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
