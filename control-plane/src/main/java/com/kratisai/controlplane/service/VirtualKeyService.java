package com.kratisai.controlplane.service;

import com.kratisai.controlplane.client.litellm.LiteLLMClient;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.*;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class VirtualKeyService {

    private static final Logger logger = LoggerFactory.getLogger(VirtualKeyService.class);

    private final LiteLLMClient liteLLMClient;

    public VirtualKeyService(LiteLLMClient liteLLMClient) {
        this.liteLLMClient = liteLLMClient;
    }

    // deleteKey returns a response the client already validates by throwing on HTTP errors.
    @SuppressFBWarnings({"RV_RETURN_VALUE_IGNORED", "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT"})
    public String generateKey(String keyAlias, List<String> litellmModelNames) {
        logger.info("Generating virtual key with alias '{}' and models {}", keyAlias, litellmModelNames);

        GenerateKeyResponse response;
        try {
            response = liteLLMClient.generateKey(new GenerateKeyRequest(keyAlias, litellmModelNames));
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                logger.warn(
                        "Virtual key with alias '{}' already exists in LiteLLM. Revoking and regenerating.", keyAlias);
                try {
                    liteLLMClient.deleteKey(new DeleteKeyRequest(null, List.of(keyAlias)));
                } catch (Exception revokeEx) {
                    logger.error("Failed to revoke existing key with alias '{}'", keyAlias, revokeEx);
                }
                response = liteLLMClient.generateKey(new GenerateKeyRequest(keyAlias, litellmModelNames));
            } else {
                throw e;
            }
        }

        logger.info("Successfully generated virtual key with alias '{}'", keyAlias);
        return response.key();
    }

    // updateKey returns a response the client already validates by throwing on HTTP errors.
    @SuppressFBWarnings({"RV_RETURN_VALUE_IGNORED", "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT"})
    public void updateKeyModels(String virtualKeyToken, List<String> litellmModelNames) {
        logger.info("Updating virtual key models to {} for token {}", litellmModelNames, virtualKeyToken);
        try {
            liteLLMClient.updateKey(new UpdateKeyRequest(virtualKeyToken, litellmModelNames));
        } catch (Exception e) {
            logger.error("Failed to update models for virtual key token {}", virtualKeyToken, e);
            throw e;
        }
    }

    public LlmUsageSnapshot fetchUsage(String virtualKeyToken) {
        logger.debug("Fetching usage for virtual key token");
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<KeyInfoResponse> keyInfo = executor.submit(() -> liteLLMClient.keyInfo(virtualKeyToken));
            Future<List<SpendLogEntry>> spendLogs = executor.submit(() -> liteLLMClient.spendLogs(virtualKeyToken));
            LlmUsageSnapshot result = buildSnapshot(keyInfo.get(), spendLogs.get());
            logger.debug("Fetched usage: tokens={}, spend={}", result.totalTokens(), result.spend());
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching LLM usage", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to fetch LLM usage", cause);
        }
    }

    private static LlmUsageSnapshot buildSnapshot(KeyInfoResponse keyInfo, List<SpendLogEntry> spendLogs) {
        KeyInfoData info = keyInfo.info();
        double spend = info != null && info.spend() != null ? info.spend() : 0.0;
        long totalTokens = spendLogs.stream()
                .mapToLong(entry -> orZero(entry.totalTokens()))
                .sum();
        long promptTokens = spendLogs.stream()
                .mapToLong(entry -> orZero(entry.promptTokens()))
                .sum();
        long completionTokens = spendLogs.stream()
                .mapToLong(entry -> orZero(entry.completionTokens()))
                .sum();
        return new LlmUsageSnapshot(spend, totalTokens, promptTokens, completionTokens);
    }

    private static long orZero(Long value) {
        return value != null ? value : 0L;
    }

    // deleteKey returns a response the client already validates by throwing on HTTP errors.
    @SuppressFBWarnings({"RV_RETURN_VALUE_IGNORED", "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT"})
    public void revokeKey(String virtualKeyToken) {
        logger.info("Revoking virtual key by token");
        liteLLMClient.deleteKey(new DeleteKeyRequest(List.of(virtualKeyToken), null));
        logger.info("Successfully revoked virtual key");
    }
}
