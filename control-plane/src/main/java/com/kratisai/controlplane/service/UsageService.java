package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.restdto.ModelUsageDto;
import com.kratisai.controlplane.api.restdto.UsageLogEntryDto;
import com.kratisai.controlplane.api.restdto.UsageSummaryDto;
import com.kratisai.controlplane.model.CanvasEntity;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ChatUsageSession;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.model.UsageType;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ChatUsageSessionRepository;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UsageService {

    private static final Logger logger = LoggerFactory.getLogger(UsageService.class);

    private final TeamMemberRepository teamMemberRepository;
    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final IngestionBatchRepository ingestionBatchRepository;
    private final ChatRepository chatRepository;
    private final ChatUsageSessionRepository chatUsageSessionRepository;
    private final TeamWikiBatchRepository teamWikiBatchRepository;

    public UsageService(
            TeamMemberRepository teamMemberRepository,
            SandboxExecutionRepository sandboxExecutionRepository,
            IngestionBatchRepository ingestionBatchRepository,
            ChatRepository chatRepository,
            ChatUsageSessionRepository chatUsageSessionRepository,
            TeamWikiBatchRepository teamWikiBatchRepository) {
        this.teamMemberRepository = teamMemberRepository;
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.chatRepository = chatRepository;
        this.chatUsageSessionRepository = chatUsageSessionRepository;
        this.teamWikiBatchRepository = teamWikiBatchRepository;
    }

    private void validateMembership(UUID userId, UUID teamId) {
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
    }

    private Instant computeStartTime(String timeframe, Instant customStart) {
        if ("custom".equalsIgnoreCase(timeframe) && customStart != null) {
            return customStart;
        }
        Instant now = Instant.now();
        if (timeframe == null || timeframe.equalsIgnoreCase("24h") || timeframe.equalsIgnoreCase("last_24_hours")) {
            return now.minus(24, ChronoUnit.HOURS);
        }
        if (timeframe.equalsIgnoreCase("7d") || timeframe.equalsIgnoreCase("last_7_days")) {
            return now.minus(7, ChronoUnit.DAYS);
        }
        if (timeframe.equalsIgnoreCase("30d") || timeframe.equalsIgnoreCase("last_30_days")) {
            return now.minus(30, ChronoUnit.DAYS);
        }
        return now.minus(30, ChronoUnit.DAYS);
    }

    @Transactional(readOnly = true)
    public Page<UsageLogEntryDto> getUsageLogs(
            UUID userId,
            UUID teamId,
            String timeframe,
            Instant startDate,
            Instant endDate,
            UsageType usageType,
            String model,
            String agent,
            Pageable pageable) {
        validateMembership(userId, teamId);
        Instant start = computeStartTime(timeframe, startDate);
        Instant end = endDate != null ? endDate : Instant.now();

        List<UsageLogEntryDto> allEntries = new ArrayList<>();

        // 1. Fetch Sandbox Executions for team
        List<SandboxExecution> executions = sandboxExecutionRepository.findAll().stream()
                .filter(e -> e.getEnvironment() != null
                        && e.getEnvironment().getTeam() != null
                        && e.getEnvironment().getTeam().getId().equals(teamId))
                .filter(e -> e.getStartedAt() != null
                        && !e.getStartedAt().isBefore(start)
                        && !e.getStartedAt().isAfter(end))
                .toList();

        for (SandboxExecution exec : executions) {
            String modelName =
                    exec.getModelName() != null && !exec.getModelName().isBlank() ? exec.getModelName() : "default";
            Set<ModelUsageDto> models = Set.of(
                    new ModelUsageDto(ModelKind.CHAT, modelName, null, exec.getTotalSpend(), exec.getTotalTokens()));
            String agentName = exec.getHarness() != null ? exec.getHarness().getName() : "OpenCode";
            String userEmail = exec.getChat() != null && exec.getChat().getUser() != null
                    ? exec.getChat().getUser().getEmail()
                    : "system@kratis.ai";
            CanvasEntity canvas = exec.getCanvas();
            String title = canvas.getTitle();
            if (title.length() > 60) {
                title = title.substring(0, 60) + "...";
            }
            // Active time only: IDLE gaps (between turns or while the sandbox sleeps) are excluded.
            Long duration = exec.getTotalActiveSeconds();
            String targetUrl = exec.getChat() != null && exec.getChat().getId() != null
                    ? "/chats/" + exec.getChat().getId() + "/executions/" + exec.getId()
                    : null;

            allEntries.add(new UsageLogEntryDto(
                    "exec-" + exec.getId(),
                    exec.getStartedAt(),
                    duration,
                    title,
                    agentName,
                    exec.getStatus(),
                    userEmail,
                    models,
                    UsageType.EXECUTION,
                    exec.getTotalTokens(),
                    exec.getTotalSpend(),
                    targetUrl));
        }

        // 2. Fetch Ingestion Batches for team
        List<IngestionBatch> batches = ingestionBatchRepository.findAll().stream()
                .filter(b -> b.getRepository() != null
                        && b.getRepository().getTeam() != null
                        && b.getRepository().getTeam().getId().equals(teamId))
                .filter(b -> b.getStartedAt() != null
                        && !b.getStartedAt().isBefore(start)
                        && !b.getStartedAt().isAfter(end))
                .toList();

        for (IngestionBatch batch : batches) {
            String repoName =
                    batch.getRepository() != null ? batch.getRepository().getName() : "Repository";
            String title = "Repository Ingestion (" + repoName + ")";
            Long duration = null;
            if (batch.getStartedAt() != null && batch.getCompletedAt() != null) {
                duration = batch.getCompletedAt().getEpochSecond()
                        - batch.getStartedAt().getEpochSecond();
            }
            SandboxExecutionStatus status = SandboxExecutionStatus.COMPLETED;
            if (batch.getStatus() == IngestionStatus.FAILED) {
                status = SandboxExecutionStatus.FAILED;
            } else if (batch.getStatus() == IngestionStatus.PROCESSING || batch.getStatus() == IngestionStatus.QUEUED) {
                status = SandboxExecutionStatus.RUNNING;
            }
            Set<ModelUsageDto> models = batch.getModelUsage().stream()
                    .map(u -> new ModelUsageDto(
                            u.getModelKind(),
                            u.getModelIdentifier(),
                            u.getLitellmAlias(),
                            u.getTotalSpend(),
                            u.getTotalTokens()))
                    .collect(Collectors.toCollection(TreeSet::new));
            String targetUrl =
                    batch.getRepository() != null && batch.getRepository().getId() != null
                            ? "/repos/" + batch.getRepository().getId()
                            : null;

            allEntries.add(new UsageLogEntryDto(
                    "ingest-" + batch.getId(),
                    batch.getStartedAt(),
                    duration,
                    title,
                    "Ingestion Worker",
                    status,
                    "system@kratis.ai",
                    models,
                    UsageType.INGESTION,
                    batch.getModelUsage().stream()
                            .mapToLong(usage -> usage.getTotalTokens() != null ? usage.getTotalTokens() : 0L)
                            .sum(),
                    batch.getModelUsage().stream()
                            .mapToDouble(usage -> usage.getTotalSpend() != null ? usage.getTotalSpend() : 0.0)
                            .sum(),
                    targetUrl));
        }

        // 2b. Fetch Team Wiki Batches for team
        List<TeamWikiBatch> teamWikiBatches =
                teamWikiBatchRepository.findByTeamIdAndStartedAtBetween(teamId, start, end);
        for (TeamWikiBatch twBatch : teamWikiBatches) {
            Long duration = null;
            if (twBatch.getStartedAt() != null && twBatch.getCompletedAt() != null) {
                duration = twBatch.getCompletedAt().getEpochSecond()
                        - twBatch.getStartedAt().getEpochSecond();
            }
            SandboxExecutionStatus status = SandboxExecutionStatus.COMPLETED;
            if (twBatch.getStatus() == IngestionStatus.FAILED) {
                status = SandboxExecutionStatus.FAILED;
            } else if (twBatch.getStatus() == IngestionStatus.PROCESSING
                    || twBatch.getStatus() == IngestionStatus.QUEUED) {
                status = SandboxExecutionStatus.RUNNING;
            }
            Set<ModelUsageDto> models = twBatch.getModelUsage().stream()
                    .map(u -> new ModelUsageDto(
                            u.getModelKind(),
                            u.getModelIdentifier(),
                            u.getLitellmAlias(),
                            u.getTotalSpend(),
                            u.getTotalTokens()))
                    .collect(Collectors.toCollection(TreeSet::new));

            allEntries.add(new UsageLogEntryDto(
                    "team-wiki-" + twBatch.getId(),
                    twBatch.getStartedAt(),
                    duration,
                    "Team Wiki Build (" + twBatch.getBuildMode().name().toLowerCase(Locale.ROOT) + ")",
                    "Team Wiki Worker",
                    status,
                    "system@kratis.ai",
                    models,
                    UsageType.TEAM_WIKI,
                    twBatch.getModelUsage().stream()
                            .mapToLong(usage -> usage.getTotalTokens() != null ? usage.getTotalTokens() : 0L)
                            .sum(),
                    twBatch.getModelUsage().stream()
                            .mapToDouble(usage -> usage.getTotalSpend() != null ? usage.getTotalSpend() : 0.0)
                            .sum(),
                    "/wiki"));
        }

        // 3. Fetch Chat Usage Sessions for team
        List<ChatEntity> chats = chatRepository.findByTeamId(teamId);
        for (ChatEntity chat : chats) {
            List<ChatUsageSession> sessions = chatUsageSessionRepository.findByChatId(chat.getId());
            for (ChatUsageSession session : sessions) {
                if (session.getStartedAt() != null
                        && !session.getStartedAt().isBefore(start)
                        && !session.getStartedAt().isAfter(end)) {
                    String userEmail = chat.getUser() != null && chat.getUser().getEmail() != null
                            ? chat.getUser().getEmail()
                            : "user@kratis.ai";
                    String title = chat.getTitle() != null ? chat.getTitle() : "Ask Kratis Chat";
                    long tokens = session.getUsage().getTotalTokens() != null
                            ? session.getUsage().getTotalTokens()
                            : 0L;
                    double spend = session.getUsage().getTotalSpend() != null
                            ? session.getUsage().getTotalSpend()
                            : 0.0;
                    String modelName =
                            session.getModel() != null && !session.getModel().isBlank()
                                    ? session.getModel()
                                    : "unknown";
                    Set<ModelUsageDto> models =
                            Set.of(new ModelUsageDto(ModelKind.CHAT, modelName, null, spend, tokens));
                    String targetUrl = chat.getId() != null ? "/chats/" + chat.getId() : null;

                    allEntries.add(new UsageLogEntryDto(
                            "chat-session-" + session.getId(),
                            session.getStartedAt(),
                            null,
                            title,
                            null,
                            session.isEnded() ? SandboxExecutionStatus.COMPLETED : SandboxExecutionStatus.RUNNING,
                            userEmail,
                            models,
                            UsageType.CHAT,
                            tokens,
                            spend,
                            targetUrl));
                }
            }
        }

        // Apply filters
        List<UsageLogEntryDto> filtered = allEntries.stream()
                .filter(e -> {
                    if (usageType != null && e.usageType() != usageType) {
                        return false;
                    }
                    if (model != null && !model.isBlank() && !containsModel(e.models(), model)) {
                        return false;
                    }
                    if (agent != null && !agent.isBlank()) {
                        if (e.agentName() == null || !e.agentName().equalsIgnoreCase(agent)) {
                            return false;
                        }
                    }
                    return true;
                })
                .sorted((a, b) -> b.timestamp().compareTo(a.timestamp()))
                .toList();

        int page = pageable.getPageNumber();
        int size = pageable.getPageSize();
        int from = Math.min(page * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        List<UsageLogEntryDto> pageContent = filtered.subList(from, to);

        return new PageImpl<>(pageContent, pageable, filtered.size());
    }

    @Transactional(readOnly = true)
    public UsageSummaryDto getUsageSummary(
            UUID userId, UUID teamId, String timeframe, Instant startDate, Instant endDate) {
        validateMembership(userId, teamId);
        Page<UsageLogEntryDto> allEntries =
                getUsageLogs(userId, teamId, timeframe, startDate, endDate, null, null, null, PageRequest.of(0, 10000));

        double totalCost = 0.0;
        long totalTokens = 0L;
        long totalOperations = allEntries.getTotalElements();

        Map<String, Double> costByModel = new HashMap<>();
        Map<String, Long> tokensByModel = new HashMap<>();
        Map<String, Long> countByModel = new HashMap<>();

        Map<String, Double> costByAgentTime = new HashMap<>();
        Map<String, Long> tokensByAgentTime = new HashMap<>();
        Map<String, Long> countByAgentTime = new HashMap<>();

        Map<String, Double> costByAgentCost = new HashMap<>();
        Map<String, Long> tokensByAgentCost = new HashMap<>();
        Map<String, Long> countByAgentCost = new HashMap<>();

        Map<Instant, TimeSeriesAccumulator> timeSeriesMap = new TreeMap<>();

        for (UsageLogEntryDto entry : allEntries.getContent()) {
            totalCost += entry.totalSpend();
            totalTokens += entry.totalTokens();

            // Per-model statistics come from the DTO's per-model rows,
            // so each model is attributed exactly its own spend and tokens.
            for (ModelUsageDto m : entry.models()) {
                accumulateModel(
                        costByModel,
                        tokensByModel,
                        countByModel,
                        m.modelIdentifier(),
                        m.totalSpend() != null ? m.totalSpend() : 0.0,
                        m.totalTokens() != null ? m.totalTokens() : 0L);
            }

            // Agent share by time (using duration or count) - only when agentName is present
            if (entry.agentName() != null) {
                long timeWeight = entry.durationSeconds() != null ? Math.max(entry.durationSeconds(), 1L) : 1L;
                costByAgentTime.put(
                        entry.agentName(), costByAgentTime.getOrDefault(entry.agentName(), 0.0) + timeWeight);
                tokensByAgentTime.put(
                        entry.agentName(), tokensByAgentTime.getOrDefault(entry.agentName(), 0L) + timeWeight);
                countByAgentTime.put(entry.agentName(), countByAgentTime.getOrDefault(entry.agentName(), 0L) + 1L);

                // Agent share by cost
                costByAgentCost.put(
                        entry.agentName(), costByAgentCost.getOrDefault(entry.agentName(), 0.0) + entry.totalSpend());
                tokensByAgentCost.put(
                        entry.agentName(), tokensByAgentCost.getOrDefault(entry.agentName(), 0L) + entry.totalTokens());
                countByAgentCost.put(entry.agentName(), countByAgentCost.getOrDefault(entry.agentName(), 0L) + 1L);
            }

            // Time-series binning by hour/day
            if (entry.timestamp() != null) {
                Instant hourKey = entry.timestamp().truncatedTo(ChronoUnit.HOURS);
                timeSeriesMap
                        .computeIfAbsent(hourKey, k -> new TimeSeriesAccumulator())
                        .add(entry.totalSpend(), entry.totalTokens());
            }
        }

        List<UsageSummaryDto.TimeSeriesPointDto> timeSeries = timeSeriesMap.entrySet().stream()
                .map(e -> new UsageSummaryDto.TimeSeriesPointDto(e.getKey(), e.getValue().cost, e.getValue().tokens))
                .toList();

        List<UsageSummaryDto.ShareBreakdownDto> modelShare =
                buildShareBreakdown(costByModel, tokensByModel, countByModel, totalCost);
        List<UsageSummaryDto.ShareBreakdownDto> agentShareByTime = buildShareBreakdown(
                costByAgentTime,
                tokensByAgentTime,
                countByAgentTime,
                costByAgentTime.values().stream()
                        .mapToDouble(Double::doubleValue)
                        .sum());
        List<UsageSummaryDto.ShareBreakdownDto> agentShareByCost =
                buildShareBreakdown(costByAgentCost, tokensByAgentCost, countByAgentCost, totalCost);

        return new UsageSummaryDto(
                totalCost, totalTokens, totalOperations, timeSeries, modelShare, agentShareByTime, agentShareByCost);
    }

    private List<UsageSummaryDto.ShareBreakdownDto> buildShareBreakdown(
            Map<String, Double> costMap, Map<String, Long> tokenMap, Map<String, Long> countMap, double totalMetric) {
        List<UsageSummaryDto.ShareBreakdownDto> list = new ArrayList<>();
        for (Map.Entry<String, Double> entry : costMap.entrySet()) {
            String key = entry.getKey();
            double cost = entry.getValue();
            long tokens = tokenMap.getOrDefault(key, 0L);
            long count = countMap.getOrDefault(key, 0L);
            double pct = totalMetric > 0 ? (cost / totalMetric) * 100.0 : 0.0;
            list.add(new UsageSummaryDto.ShareBreakdownDto(key, cost, tokens, count, pct));
        }
        list.sort((a, b) -> Double.compare(b.cost(), a.cost()));
        return list;
    }

    private static void accumulateModel(
            Map<String, Double> costByModel,
            Map<String, Long> tokensByModel,
            Map<String, Long> countByModel,
            String modelName,
            double cost,
            long tokens) {
        costByModel.put(modelName, costByModel.getOrDefault(modelName, 0.0) + cost);
        tokensByModel.put(modelName, tokensByModel.getOrDefault(modelName, 0L) + tokens);
        countByModel.put(modelName, countByModel.getOrDefault(modelName, 0L) + 1L);
    }

    private static boolean containsModel(Set<ModelUsageDto> models, String model) {
        return models.stream().anyMatch(m -> m.modelIdentifier().equalsIgnoreCase(model));
    }

    private static class TimeSeriesAccumulator {
        double cost = 0.0;
        long tokens = 0L;

        void add(double c, long t) {
            this.cost += c;
            this.tokens += t;
        }
    }
}
