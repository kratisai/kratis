package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.DiffSectionsResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.DiffSectionsStatus;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.service.BlobStorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

@Component
public class EnvironmentDiffSectionsRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.DiffSections, EnvironmentResponsePayload> {

    private final EnvironmentExecutionGuard executionGuard;
    private final BlobStorageService blobStorageService;

    public EnvironmentDiffSectionsRpcHandler(
            EnvironmentExecutionGuard executionGuard, BlobStorageService blobStorageService) {
        this.executionGuard = executionGuard;
        this.blobStorageService = blobStorageService;
    }

    @Override
    public String getMethodName() {
        return EnvironmentRpcPayload.DiffSections.METHOD;
    }

    @Override
    public Class<EnvironmentRpcPayload.DiffSections> getPayloadType() {
        return EnvironmentRpcPayload.DiffSections.class;
    }

    @Override
    @Transactional
    public Flux<EnvironmentResponsePayload> handle(
            String sessionId, Object requestId, EnvironmentRpcPayload.DiffSections params) {
        UUID execId = UUID.fromString(params.executionId());
        executionGuard.requireExecutionInEnvironment(sessionId, execId);

        List<String> missing = new ArrayList<>();

        for (EnvironmentConnectorResult.DiffSectionPart part : params.parts()) {
            String sha = part.sha();
            String finalBlobPath = "diffs/" + execId + "/" + sha;
            if (blobStorageService.exists(finalBlobPath)) {
                continue;
            }

            if (part.partCount() == 1) {
                byte[] dataBytes = part.data().getBytes(StandardCharsets.UTF_8);
                String computedSha = computeSha256(dataBytes);
                if (!computedSha.equalsIgnoreCase(sha)) {
                    throw new RpcErrorException(JsonRpcError.InvalidParams("SHA mismatch for section " + sha));
                }
                blobStorageService.putObject(
                        finalBlobPath, new ByteArrayInputStream(dataBytes), dataBytes.length, "text/plain");
            } else {
                String partBlobPath = "diffs/" + execId + "/" + sha + ".part" + part.partIndex();
                byte[] partBytes = part.data().getBytes(StandardCharsets.UTF_8);
                blobStorageService.putObject(
                        partBlobPath, new ByteArrayInputStream(partBytes), partBytes.length, "text/plain");

                boolean allPartsPresent = true;
                for (int i = 0; i < part.partCount(); i++) {
                    if (!blobStorageService.exists("diffs/" + execId + "/" + sha + ".part" + i)) {
                        allPartsPresent = false;
                        break;
                    }
                }

                if (allPartsPresent) {
                    StringBuilder assembled = new StringBuilder();
                    for (int i = 0; i < part.partCount(); i++) {
                        String pPath = "diffs/" + execId + "/" + sha + ".part" + i;
                        try (InputStream is = blobStorageService.getObject(pPath)) {
                            assembled.append(new String(is.readAllBytes(), StandardCharsets.UTF_8));
                        } catch (IOException e) {
                            throw new RuntimeException("Failed reading part " + pPath, e);
                        }
                    }
                    byte[] assembledBytes = assembled.toString().getBytes(StandardCharsets.UTF_8);
                    String computedSha = computeSha256(assembledBytes);
                    if (!computedSha.equalsIgnoreCase(sha)) {
                        throw new RpcErrorException(
                                JsonRpcError.InvalidParams("SHA mismatch for assembled section " + sha));
                    }
                    blobStorageService.putObject(
                            finalBlobPath,
                            new ByteArrayInputStream(assembledBytes),
                            assembledBytes.length,
                            "text/plain");

                    for (int i = 0; i < part.partCount(); i++) {
                        blobStorageService.deleteObject("diffs/" + execId + "/" + sha + ".part" + i);
                    }
                } else {
                    missing.add(sha);
                }
            }
        }

        return Flux.just(new DiffSectionsResult(DiffSectionsStatus.STORED, missing));
    }

    private static String computeSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
