package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.UsageLogEntryDto;
import com.kratisai.controlplane.api.restdto.UsageSummaryDto;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.service.UsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams/{teamId}")
@Tag(name = "Usage", description = "Usage analytics and log endpoints")
@SecurityRequirement(name = "bearerAuth")
public class UsageLogController {

    private final UsageService usageService;

    public UsageLogController(UsageService usageService) {
        this.usageService = usageService;
    }

    @GetMapping("/usage-logs")
    @Operation(
            summary = "Get paginated usage logs",
            description =
                    "Returns human-friendly usage log entries spanning Executions, Ingestion Batches, and Chat Sessions")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Usage logs retrieved successfully",
                        content = @Content(schema = @Schema(implementation = UsageLogEntryDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content)
            })
    public ResponseEntity<Page<UsageLogEntryDto>> getUsageLogs(
            @PathVariable UUID teamId,
            @RequestParam(required = false) String timeframe,
            @RequestParam(required = false) Instant startDate,
            @RequestParam(required = false) Instant endDate,
            @RequestParam(required = false) String usageType,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String agent,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID userId = SecurityUtil.getCurrentUserId();
        Page<UsageLogEntryDto> logs = usageService.getUsageLogs(
                userId, teamId, timeframe, startDate, endDate, usageType, model, agent, PageRequest.of(page, size));
        return ResponseEntity.ok(logs);
    }

    @GetMapping("/usage-summary")
    @Operation(
            summary = "Get usage analytics summary",
            description =
                    "Returns aggregated KPI totals, time-series data, and breakdown metrics for Model Share and Agent Share")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Usage summary retrieved successfully",
                        content = @Content(schema = @Schema(implementation = UsageSummaryDto.class))),
                @ApiResponse(responseCode = "403", description = "Not a member of this team", content = @Content)
            })
    public ResponseEntity<UsageSummaryDto> getUsageSummary(
            @PathVariable UUID teamId,
            @RequestParam(required = false) String timeframe,
            @RequestParam(required = false) Instant startDate,
            @RequestParam(required = false) Instant endDate) {
        UUID userId = SecurityUtil.getCurrentUserId();
        UsageSummaryDto summary = usageService.getUsageSummary(userId, teamId, timeframe, startDate, endDate);
        return ResponseEntity.ok(summary);
    }
}
