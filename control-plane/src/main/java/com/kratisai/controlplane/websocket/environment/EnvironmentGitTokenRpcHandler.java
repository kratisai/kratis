package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.GitTokenResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.GitCredentialResolver;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

/**
 * Serves a freshly-resolved git credential token to a sandbox credential server.
 */
@Component
public class EnvironmentGitTokenRpcHandler
        implements EnvironmentRpcHandler<EnvironmentRpcPayload.GitToken, GitTokenResult> {

    private final ExecutionEnvironmentRepository executionEnvironmentRepository;
    private final SandboxExecutionRepository sandboxExecutionRepository;
    private final EnvironmentSessionRegistry sessionRegistry;
    private final GitCredentialResolver credentialResolver;

    public EnvironmentGitTokenRpcHandler(
            ExecutionEnvironmentRepository executionEnvironmentRepository,
            SandboxExecutionRepository sandboxExecutionRepository,
            EnvironmentSessionRegistry sessionRegistry,
            GitCredentialResolver credentialResolver) {
        this.executionEnvironmentRepository = executionEnvironmentRepository;
        this.sandboxExecutionRepository = sandboxExecutionRepository;
        this.sessionRegistry = sessionRegistry;
        this.credentialResolver = credentialResolver;
    }

    @Override
    public String getMethodName() {
        return EnvironmentRpcPayload.GitToken.METHOD;
    }

    @Override
    public Class<EnvironmentRpcPayload.GitToken> getPayloadType() {
        return EnvironmentRpcPayload.GitToken.class;
    }

    @Override
    @Transactional
    public Flux<GitTokenResult> handle(String sessionId, Object requestId, EnvironmentRpcPayload.GitToken params) {
        Optional<UUID> envIdOpt = sessionRegistry.getEnvironmentId(sessionId);
        if (envIdOpt.isEmpty()) {
            throw new RpcErrorException(
                    JsonRpcError.error(-32001, "Not registered as environment session", "Session not registered"));
        }

        ExecutionEnvironment env = executionEnvironmentRepository
                .findById(envIdOpt.get())
                .orElseThrow(() -> new RpcErrorException(
                        JsonRpcError.error(-32001, "Environment not found", "Environment not found in database")));

        SandboxExecution execution = sandboxExecutionRepository
                .findFirstByEnvironmentIdOrderByStartedAtDesc(env.getId())
                .orElseThrow(() -> new RpcErrorException(
                        JsonRpcError.error(-32001, "No execution for environment", "Environment has no execution")));

        Repository repository = execution.getRepository();
        if (repository == null) {
            throw new RpcErrorException(
                    JsonRpcError.error(-32001, "No repository for execution", "Execution has no repository"));
        }

        RepoCredential credential = repository.getCredential();
        if (credential == null) {
            throw new RpcErrorException(
                    JsonRpcError.error(-32001, "No credential for repository", "Repository has no credential"));
        }

        GitAuthMaterial auth = credentialResolver.resolve(credential);
        String token = auth.maybeToken().orElse("");
        if (token.isEmpty()) {
            throw new RpcErrorException(JsonRpcError.error(
                    -32001, "Credential has no token", "Credential type does not provide a git token"));
        }

        return Flux.just(new GitTokenResult(token));
    }
}
