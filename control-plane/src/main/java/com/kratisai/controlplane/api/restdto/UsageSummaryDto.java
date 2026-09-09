package com.kratisai.controlplane.api.restdto;

import java.time.Instant;
import java.util.List;

public record UsageSummaryDto(
        double totalCost,
        long totalTokens,
        long totalOperations,
        List<TimeSeriesPointDto> timeSeries,
        List<ShareBreakdownDto> modelShare,
        List<ShareBreakdownDto> agentShareByTime,
        List<ShareBreakdownDto> agentShareByCost) {

    public record TimeSeriesPointDto(Instant timestamp, double cost, long tokens) {}

    public record ShareBreakdownDto(String label, double cost, long tokens, long count, double percentage) {}
}
