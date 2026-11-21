package com.kratisai.controlplane.api.restdto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.UsageType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Arrays;
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
                UsageType.EXECUTION,
                100L,
                0.05,
                "/chats/chat-1/executions/exec-1");
        assertThat(dto.id()).isEqualTo("id-1");
        assertThat(dto.timestamp()).isEqualTo(now);
        assertThat(dto.durationSeconds()).isEqualTo(10L);
        assertThat(dto.activityTitle()).isEqualTo("Title");
        assertThat(dto.agentName()).isEqualTo("Agent");
        assertThat(dto.status()).isEqualTo(SandboxExecutionStatus.COMPLETED);
        assertThat(dto.userEmail()).isEqualTo("user@example.com");
        assertThat(dto.modelIdentifiers()).containsExactly("gpt-4o");
        assertThat(dto.usageType()).isEqualTo(UsageType.EXECUTION);
        assertThat(dto.totalTokens()).isEqualTo(100L);
        assertThat(dto.totalSpend()).isEqualTo(0.05);
        assertThat(dto.targetUrl()).isEqualTo("/chats/chat-1/executions/exec-1");
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
                UsageType.INGESTION,
                500L,
                0.01,
                "/repos/repo-1");
        assertThat(dto.modelIdentifiers()).containsExactly("gpt-4o", "text-embedding-3-small");
        assertThat(dto.targetUrl()).isEqualTo("/repos/repo-1");
    }

    @Test
    void usageLogEntryDto_allowsNullTargetUrl() {
        UsageLogEntryDto dto = new UsageLogEntryDto(
                "id-4",
                Instant.now(),
                null,
                "Orphan Entry",
                "Agent",
                SandboxExecutionStatus.COMPLETED,
                "user@example.com",
                Set.of("gpt-4o"),
                UsageType.EXECUTION,
                10L,
                0.0,
                null);
        assertThat(dto.targetUrl()).isNull();
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
                        UsageType.INGESTION,
                        0L,
                        0.0,
                        null))
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

    @Test
    void usageType_wireNamesAreStable() throws Exception {
        assertThat(Arrays.stream(UsageType.values()).map(Enum::name))
                .containsExactlyInAnyOrder("CHAT", "EXECUTION", "INGESTION", "TEAM_WIKI");

        ObjectMapper mapper = new ObjectMapper();
        for (UsageType type : UsageType.values()) {
            String json = mapper.writeValueAsString(type);
            assertThat(json).isEqualTo("\"" + type.name() + "\"");
            assertThat(mapper.readValue(json, UsageType.class)).isEqualTo(type);
        }
    }

    @Test
    void usageLogEntryDto_serializesUsageTypeAsPlainName() throws Exception {
        UsageLogEntryDto dto = new UsageLogEntryDto(
                "id-1",
                Instant.now(),
                10L,
                "Title",
                "Agent",
                SandboxExecutionStatus.COMPLETED,
                "user@example.com",
                Set.of("gpt-4o"),
                UsageType.EXECUTION,
                100L,
                0.05,
                "/chats/chat-1/executions/exec-1");

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(dto);
        assertThat(json).contains("\"usageType\":\"EXECUTION\"");
    }
}
