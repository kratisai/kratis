package com.kratisai.controlplane.websocket.environment;

import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Guards environment (sidecar) sessions against naming executions that do not belong to their
 * environment. The {@code executionId} in an inbound notification is echoed by the sidecar, so it
 * must not be trusted on its own: every execution referenced by a session must belong to the
 * environment that session is authenticated for.
 */
@Component
public class EnvironmentExecutionGuard {

    private final EnvironmentSessionRegistry sessionRegistry;
    private final SandboxExecutionRepository executionRepository;

    public EnvironmentExecutionGuard(
            EnvironmentSessionRegistry sessionRegistry, SandboxExecutionRepository executionRepository) {
        this.sessionRegistry = sessionRegistry;
        this.executionRepository = executionRepository;
    }

    /** Resolve the environment a session is authenticated for, rejecting unregistered sessions. */
    public UUID requireEnvironmentId(String sessionId) {
        return sessionRegistry
                .getEnvironmentId(sessionId)
                .orElseThrow(() -> new RpcErrorException(
                        JsonRpcError.error(-32001, "Registration required", "Environment session not registered")));
    }

    /**
     * Load an execution and verify it belongs to the session's environment.
     *
     * @throws IllegalArgumentException if the execution does not exist
     * @throws RpcErrorException if the session is unregistered or the execution belongs to another
     *     environment
     */
    public SandboxExecution requireExecutionInEnvironment(String sessionId, UUID executionId) {
        UUID environmentId = requireEnvironmentId(sessionId);
        SandboxExecution execution = executionRepository
                .findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Execution not found: " + executionId));
        verifyExecutionInEnvironment(execution, environmentId);
        return execution;
    }

    /**
     * Verify a loaded execution belongs to the given environment. For handlers that treat a missing
     * execution differently (e.g. cancelling a HITL request) after their own lookup.
     *
     * @throws RpcErrorException if the execution belongs to another environment
     */
    public void verifyExecutionInEnvironment(SandboxExecution execution, UUID environmentId) {
        if (execution.getEnvironment() == null
                || !environmentId.equals(execution.getEnvironment().getId())) {
            throw new RpcErrorException(JsonRpcError.NotAuthorised(
                    "Execution " + execution.getId() + " does not belong to environment " + environmentId));
        }
    }
}
