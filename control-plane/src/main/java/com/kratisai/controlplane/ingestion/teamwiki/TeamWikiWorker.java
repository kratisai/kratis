package com.kratisai.controlplane.ingestion.teamwiki;

import com.kratisai.controlplane.config.LiteLLMProperties;
import com.kratisai.controlplane.ingestion.write.SemanticIndexingService;
import com.kratisai.controlplane.model.IngestionStatus;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamWikiBatch;
import com.kratisai.controlplane.model.TeamWikiModelUsage;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.CtxEmbeddingRepository;
import com.kratisai.controlplane.repository.CtxWikiPageRepository;
import com.kratisai.controlplane.repository.TeamWikiBatchRepository;
import com.kratisai.controlplane.service.LiteLLMProvisioningService;
import com.kratisai.controlplane.service.VirtualKeyService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class TeamWikiWorker {

    private static final Logger logger = LoggerFactory.getLogger(TeamWikiWorker.class);

    private final TeamWikiBatchRepository teamWikiBatchRepository;
    private final CtxWikiPageRepository ctxWikiPageRepository;
    private final CtxEmbeddingRepository ctxEmbeddingRepository;
    private final TeamWikiBatchLogService batchLogService;
    private final TeamWikiGenerationService generationService;
    private final SemanticIndexingService semanticIndexingService;
    private final VirtualKeyService virtualKeyService;
    private final LiteLLMProvisioningService litellmProvisioningService;
    private final LiteLLMProperties litellmProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public TeamWikiWorker(
            TeamWikiBatchRepository teamWikiBatchRepository,
            CtxWikiPageRepository ctxWikiPageRepository,
            CtxEmbeddingRepository ctxEmbeddingRepository,
            TeamWikiBatchLogService batchLogService,
            TeamWikiGenerationService generationService,
            SemanticIndexingService semanticIndexingService,
            VirtualKeyService virtualKeyService,
            LiteLLMProvisioningService litellmProvisioningService,
            LiteLLMProperties litellmProperties,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.teamWikiBatchRepository = teamWikiBatchRepository;
        this.ctxWikiPageRepository = ctxWikiPageRepository;
        this.ctxEmbeddingRepository = ctxEmbeddingRepository;
        this.batchLogService = batchLogService;
        this.generationService = generationService;
        this.semanticIndexingService = semanticIndexingService;
        this.virtualKeyService = virtualKeyService;
        this.litellmProvisioningService = litellmProvisioningService;
        this.litellmProperties = litellmProperties;
        this.eventPublisher = eventPublisher;
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate = tt;
    }

    @Async("teamWikiTaskExecutor")
    public void runBuild(UUID batchId) {
        logger.info("Starting asynchronous team wiki build for batch {}", batchId);
        TeamWikiBatchLogService.BatchLogger batchLogger = null;
        boolean activated = false;
        TeamWikiGenerationService.GenerationResult genResult = null;

        try {
            batchLogger = transactionTemplate.execute(status -> markBatchStarted(batchId));
            transactionTemplate.executeWithoutResult(status -> generateBatchVirtualKey(batchId));
            genResult = generationService.generate(batchId, batchLogger);
            batchLogger.info("GENERATE_WIKI", "Generated " + genResult.pageCount() + " team wiki page(s).");

            transactionTemplate.executeWithoutResult(status -> {
                TeamWikiBatch batch = loadBatch(batchId);
                semanticIndexingService.indexTeamWikiBatch(batch, batch.getTeam());
            });
            batchLogger.info("GENERATE_INDEX", "Semantic indexing completed.");

            long totalToolCalls = genResult.toolCalls();
            transactionTemplate.executeWithoutResult(status -> activateBatch(batchId, totalToolCalls));
            activated = true;
        } catch (Throwable e) {
            logger.error("Team wiki batch {} failed during execution", batchId, e);
            TeamWikiBatchLogService.BatchLogger finalLogger = batchLogger;
            transactionTemplate.executeWithoutResult(status -> markBatchFailed(batchId, e, finalLogger));
        } finally {
            finalizeBatchUsageAndRevokeKey(batchId);
        }

        if (activated) {
            try {
                batchLogger.info("SUCCESS", "Team wiki build activated (" + genResult.pageCount() + " pages).");
            } catch (Exception e) {
                logger.warn("Failed to append final success log for team wiki batch {}: {}", batchId, e.getMessage());
            }
        }
    }

    private TeamWikiBatchLogService.BatchLogger markBatchStarted(UUID batchId) {
        TeamWikiBatch batch = loadBatch(batchId);
        batch.setStatus(IngestionStatus.PROCESSING);
        teamWikiBatchRepository.saveAndFlush(batch);
        Team team = batch.getTeam();
        eventPublisher.publishEvent(new TeamEntityChangedEvent(team.getId(), TeamEntityType.TEAM_WIKI));

        var batchLogger = new TeamWikiBatchLogService.BatchLogger(batchId, team.getId(), batchLogService);
        batchLogger.info(
                "START",
                "Starting " + batch.getBuildMode() + " team wiki build (" + batch.getTriggerKind() + ") for team "
                        + team.getName() + ".");
        return batchLogger;
    }

    private void generateBatchVirtualKey(UUID batchId) {
        TeamWikiBatch batch = loadBatch(batchId);
        Team team = batch.getTeam();
        var chatProvider = team.getIngestionProvider();
        var chatModel = team.getIngestionModel();
        if (chatProvider == null || chatModel == null || chatModel.isBlank()) {
            throw new IllegalStateException("Team " + team.getId() + " has no ingestion provider/model configured.");
        }
        var embeddingProvider = team.getEmbeddingProvider();
        var embeddingModel = team.getEmbeddingModel();
        if (embeddingProvider == null || embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalStateException("Team " + team.getId() + " has no embedding provider/model configured.");
        }

        String chatAlias = litellmProvisioningService.buildLiteLLMModelName(chatProvider, chatModel);
        String embeddingAlias = litellmProvisioningService.buildLiteLLMModelName(embeddingProvider, embeddingModel);
        String keyAlias = litellmProvisioningService.buildVirtualKeyAlias(
                LiteLLMProvisioningService.VirtualKeyScope.TEAM_WIKI, batchId);
        String token = virtualKeyService.generateKey(keyAlias, List.of(chatAlias, embeddingAlias));

        batch.getModelUsage().clear();
        teamWikiBatchRepository.flush();
        TeamWikiModelUsage chatUsage = new TeamWikiModelUsage(batch, ModelKind.CHAT, chatModel, chatAlias);
        chatUsage.setVirtualKey(token);
        batch.getModelUsage().add(chatUsage);
        TeamWikiModelUsage embeddingUsage =
                new TeamWikiModelUsage(batch, ModelKind.EMBEDDING, embeddingModel, embeddingAlias);
        embeddingUsage.setVirtualKey(token);
        batch.getModelUsage().add(embeddingUsage);
        teamWikiBatchRepository.saveAndFlush(batch);
    }

    private void activateBatch(UUID batchId, long toolCalls) {
        TeamWikiBatch batch = loadBatch(batchId);
        batch.setStatus(IngestionStatus.SUCCESS);
        batch.setCompletedAt(Instant.now());
        batch.setTotalToolCalls(toolCalls);
        batch.setActive(true);
        teamWikiBatchRepository.saveAndFlush(batch);
        teamWikiBatchRepository.deactivateOtherBatches(batch.getTeam().getId(), batch.getId());
        eventPublisher.publishEvent(new TeamEntityChangedEvent(batch.getTeam().getId(), TeamEntityType.TEAM_WIKI));
    }

    private void markBatchFailed(UUID batchId, Throwable e, TeamWikiBatchLogService.BatchLogger batchLogger) {
        ctxEmbeddingRepository.deleteByTeamWikiBatchId(batchId);
        ctxWikiPageRepository.deleteByTeamWikiBatchId(batchId);
        TeamWikiBatch batch = loadBatch(batchId);
        batch.setStatus(IngestionStatus.FAILED);
        batch.setErrorMessage(e.getMessage());
        batch.setCompletedAt(Instant.now());
        batch.setActive(false);
        teamWikiBatchRepository.saveAndFlush(batch);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(batch.getTeam().getId(), TeamEntityType.TEAM_WIKI));
        if (batchLogger != null) {
            batchLogger.error("FAILED", "Team wiki build failed: " + e.getMessage());
        }
    }

    private void finalizeBatchUsageAndRevokeKey(UUID batchId) {
        KeyLookup lookup;
        try {
            lookup = transactionTemplate.execute(status -> {
                TeamWikiBatch batch = loadBatch(batchId);
                String token = batch.modelUsageFor(ModelKind.CHAT)
                        .map(TeamWikiModelUsage::getVirtualKey)
                        .orElse(null);
                List<String> aliases = batch.getModelUsage().stream()
                        .map(TeamWikiModelUsage::getLitellmAlias)
                        .filter(alias -> alias != null && !alias.isBlank())
                        .toList();
                return new KeyLookup(batch.getTeam().getId(), token, aliases);
            });
        } catch (Exception e) {
            logger.warn("Failed to retrieve virtual key for team wiki batch {}: {}", batchId, e.getMessage());
            return;
        }
        if (lookup == null || lookup.token() == null) {
            return;
        }

        Map<String, LlmUsageSnapshot> usageByAlias =
                fetchUntilComplete(batchId, lookup.token(), lookup.expectedAliases());
        if (usageByAlias != null) {
            transactionTemplate.executeWithoutResult(status -> {
                TeamWikiBatch batch = loadBatch(batchId);
                for (TeamWikiModelUsage modelUsage : batch.getModelUsage()) {
                    if (modelUsage.getLitellmAlias() == null) {
                        continue;
                    }
                    LlmUsageSnapshot perModel = usageByAlias.get(modelUsage.getLitellmAlias());
                    if (perModel != null) {
                        modelUsage.apply(perModel);
                    }
                }
                teamWikiBatchRepository.saveAndFlush(batch);
                eventPublisher.publishEvent(new TeamEntityChangedEvent(lookup.teamId(), TeamEntityType.USAGE));
            });
        }

        try {
            virtualKeyService.revokeKey(lookup.token());
        } catch (Exception e) {
            logger.warn("Failed to revoke virtual key for team wiki batch {}: {}", batchId, e.getMessage());
        }
    }

    private @Nullable Map<String, LlmUsageSnapshot> fetchUntilComplete(
            UUID batchId, String token, List<String> expectedAliases) {
        long deadline = System.currentTimeMillis()
                + litellmProperties.getUsageFinalizeDelay().toMillis();
        while (true) {
            Map<String, LlmUsageSnapshot> usageByAlias;
            try {
                usageByAlias = virtualKeyService.fetchUsageByModel(token);
            } catch (Exception e) {
                logger.warn("Failed to fetch per-model usage for team wiki batch {}: {}", batchId, e.getMessage());
                return null;
            }
            if (usageByAlias.keySet().containsAll(expectedAliases) || System.currentTimeMillis() >= deadline) {
                return usageByAlias;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private TeamWikiBatch loadBatch(UUID batchId) {
        return teamWikiBatchRepository
                .findByIdWithTeam(batchId)
                .orElseThrow(() -> new IllegalStateException("TeamWikiBatch not found: " + batchId));
    }

    private record KeyLookup(UUID teamId, String token, List<String> expectedAliases) {}
}
