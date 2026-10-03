package com.kratisai.controlplane.service;

import com.kratisai.controlplane.client.litellm.LiteLLMClient;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.*;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Usage for a key that must still exist. Throws when LiteLLM no longer knows the key, so callers
     * never persist a zero snapshot over previously recorded usage. Use {@link #keyExists(String)}
     * when only existence is needed.
     */
    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT") // keyInfo's throw is the point
    public LlmUsageSnapshot fetchUsage(String virtualKeyToken) {
        logger.debug("Fetching usage for virtual key token");
        // /spend/logs returns an empty list for a revoked key; call /key/info to abort on a revoked key
        liteLLMClient.keyInfo(virtualKeyToken);
        LlmUsageSnapshot result = sum(fetchUsageByModel(virtualKeyToken));
        logger.debug("Fetched usage: tokens={}, spend={}", result.totalTokens(), result.spend());
        return result;
    }

    public boolean keyExists(String virtualKeyToken) {
        if (virtualKeyToken == null || virtualKeyToken.isBlank()) {
            return false;
        }
        try {
            return liteLLMClient.keyInfo(virtualKeyToken) != null;
        } catch (Exception e) {
            logger.debug("Virtual key is unknown or expired: {}", e.getMessage());
            return false;
        }
    }

    public Map<String, LlmUsageSnapshot> fetchUsageByModel(String virtualKeyToken) {
        logger.debug("Fetching usage by model for virtual key token");
        Map<String, LlmUsageSnapshot> usageByAlias = usageByModel(liteLLMClient.spendLogs(virtualKeyToken));
        logger.debug("Fetched usage by model for {} model group(s)", usageByAlias.size());
        return usageByAlias;
    }

    private static Map<String, LlmUsageSnapshot> usageByModel(List<SpendLogEntry> spendLogs) {
        Map<String, List<SpendLogEntry>> rowsByAlias = new LinkedHashMap<>();
        for (SpendLogEntry entry : spendLogs) {
            String alias =
                    entry.modelGroup() != null && !entry.modelGroup().isBlank() ? entry.modelGroup() : entry.model();
            if (alias == null || alias.isBlank()) {
                continue;
            }
            rowsByAlias.computeIfAbsent(alias, key -> new ArrayList<>()).add(entry);
        }
        Map<String, LlmUsageSnapshot> usageByAlias = new LinkedHashMap<>();
        for (Map.Entry<String, List<SpendLogEntry>> group : rowsByAlias.entrySet()) {
            List<SpendLogEntry> rows = group.getValue();
            usageByAlias.put(
                    group.getKey(),
                    new LlmUsageSnapshot(
                            rows.stream()
                                    .mapToDouble(row -> orZero(row.spend()))
                                    .sum(),
                            rows.stream()
                                    .mapToLong(row -> orZero(row.totalTokens()))
                                    .sum(),
                            rows.stream()
                                    .mapToLong(row -> orZero(row.promptTokens()))
                                    .sum(),
                            rows.stream()
                                    .mapToLong(row -> orZero(row.completionTokens()))
                                    .sum()));
        }
        return usageByAlias;
    }

    private static LlmUsageSnapshot sum(Map<String, LlmUsageSnapshot> usageByModel) {
        double spend = 0.0;
        long totalTokens = 0L;
        long promptTokens = 0L;
        long completionTokens = 0L;
        for (LlmUsageSnapshot snapshot : usageByModel.values()) {
            spend += orZero(snapshot.spend());
            totalTokens += orZero(snapshot.totalTokens());
            promptTokens += orZero(snapshot.promptTokens());
            completionTokens += orZero(snapshot.completionTokens());
        }
        return new LlmUsageSnapshot(spend, totalTokens, promptTokens, completionTokens);
    }

    private static long orZero(Long value) {
        return value != null ? value : 0L;
    }

    private static double orZero(Double value) {
        return value != null ? value : 0.0;
    }

    // deleteKey returns a response the client already validates by throwing on HTTP errors.
    @SuppressFBWarnings({"RV_RETURN_VALUE_IGNORED", "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT"})
    public void revokeKey(String virtualKeyToken) {
        logger.info("Revoking virtual key by token");
        liteLLMClient.deleteKey(new DeleteKeyRequest(List.of(virtualKeyToken), null));
        logger.info("Successfully revoked virtual key");
    }
}
