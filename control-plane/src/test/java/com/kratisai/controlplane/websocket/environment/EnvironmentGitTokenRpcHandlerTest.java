package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.GitTokenResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.RpcErrorException;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.model.CredentialType;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import com.kratisai.controlplane.service.GitCredentialResolver;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnvironmentGitTokenRpcHandlerTest {

    @Mock
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Mock
    private SandboxExecutionRepository sandboxExecutionRepository;

    @Mock
    private EnvironmentSessionRegistry sessionRegistry;

    @Mock
    private GitCredentialResolver credentialResolver;

    private EnvironmentGitTokenRpcHandler handler;

    private final UUID envId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new EnvironmentGitTokenRpcHandler(
                executionEnvironmentRepository, sandboxExecutionRepository, sessionRegistry, credentialResolver);
    }

    private SandboxExecution executionWith(RepoCredential credential) {
        Repository repository =
                new Repository("repo", "https://github.com/org/repo.git", "main", RepositoryType.GITHUB);
        repository.setCredential(credential);

        SandboxExecution execution = new SandboxExecution();
        execution.setRepository(repository);
        return execution;
    }

    @Test
    void handle_derivesCredentialFromExecutionAndResolvesToken() {
        when(sessionRegistry.getEnvironmentId("session-1")).thenReturn(Optional.of(envId));

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(envId);
        when(executionEnvironmentRepository.findById(envId)).thenReturn(Optional.of(env));

        RepoCredential credential = new RepoCredential(new Team(), "cred", CredentialType.PAT, "encrypted");
        SandboxExecution execution = executionWith(credential);
        when(sandboxExecutionRepository.findFirstByEnvironmentIdOrderByStartedAtDesc(envId))
                .thenReturn(Optional.of(execution));
        when(credentialResolver.resolve(credential)).thenReturn(GitAuthMaterial.ofToken("fresh-token"));

        GitTokenResult result = handler.handle("session-1", 1, new EnvironmentRpcPayload.GitToken())
                .blockLast();

        assertThat(result).isNotNull();
        assertThat(result.token()).isEqualTo("fresh-token");
    }

    @Test
    void handle_noExecutionForEnvironment_isRejected() {
        when(sessionRegistry.getEnvironmentId("session-1")).thenReturn(Optional.of(envId));

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(envId);
        when(executionEnvironmentRepository.findById(envId)).thenReturn(Optional.of(env));
        when(sandboxExecutionRepository.findFirstByEnvironmentIdOrderByStartedAtDesc(envId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle("session-1", 1, new EnvironmentRpcPayload.GitToken())
                        .blockLast())
                .isInstanceOf(RpcErrorException.class);
    }

    @Test
    void handle_repositoryWithoutCredential_isRejected() {
        when(sessionRegistry.getEnvironmentId("session-1")).thenReturn(Optional.of(envId));

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(envId);
        when(executionEnvironmentRepository.findById(envId)).thenReturn(Optional.of(env));

        SandboxExecution execution = executionWith(null);
        when(sandboxExecutionRepository.findFirstByEnvironmentIdOrderByStartedAtDesc(envId))
                .thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> handler.handle("session-1", 1, new EnvironmentRpcPayload.GitToken())
                        .blockLast())
                .isInstanceOf(RpcErrorException.class);
    }

    @Test
    void handle_unregisteredSession_isRejected() {
        when(sessionRegistry.getEnvironmentId("session-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle("session-1", 1, new EnvironmentRpcPayload.GitToken())
                        .blockLast())
                .isInstanceOf(RpcErrorException.class);
    }

    @Test
    void handle_unknownEnvironment_isRejected() {
        when(sessionRegistry.getEnvironmentId("session-1")).thenReturn(Optional.of(envId));
        when(executionEnvironmentRepository.findById(envId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle("session-1", 1, new EnvironmentRpcPayload.GitToken())
                        .blockLast())
                .isInstanceOf(RpcErrorException.class);
    }

    @Test
    void handle_newRepoExecution_resolvesPublishCredential() {
        when(sessionRegistry.getEnvironmentId("session-1")).thenReturn(Optional.of(envId));

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(envId);
        when(executionEnvironmentRepository.findById(envId)).thenReturn(Optional.of(env));

        RepoCredential credential = new RepoCredential(new Team(), "cred", CredentialType.PAT, "encrypted");
        SandboxExecution execution = new SandboxExecution();
        execution.setNewRepoName("fresh-repo");
        execution.setNewRepoCredential(credential);
        when(sandboxExecutionRepository.findFirstByEnvironmentIdOrderByStartedAtDesc(envId))
                .thenReturn(Optional.of(execution));
        when(credentialResolver.resolve(credential)).thenReturn(GitAuthMaterial.ofToken("new-repo-token"));

        GitTokenResult result = handler.handle("session-1", 1, new EnvironmentRpcPayload.GitToken())
                .blockLast();

        assertThat(result).isNotNull();
        assertThat(result.token()).isEqualTo("new-repo-token");
    }

    @Test
    void handle_executionWithoutRepository_isRejected() {
        when(sessionRegistry.getEnvironmentId("session-1")).thenReturn(Optional.of(envId));

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(envId);
        when(executionEnvironmentRepository.findById(envId)).thenReturn(Optional.of(env));

        SandboxExecution execution = new SandboxExecution();
        when(sandboxExecutionRepository.findFirstByEnvironmentIdOrderByStartedAtDesc(envId))
                .thenReturn(Optional.of(execution));

        assertThatThrownBy(() -> handler.handle("session-1", 1, new EnvironmentRpcPayload.GitToken())
                        .blockLast())
                .isInstanceOf(RpcErrorException.class);
    }

    @Test
    void handle_credentialWithoutToken_isRejected() {
        when(sessionRegistry.getEnvironmentId("session-1")).thenReturn(Optional.of(envId));

        ExecutionEnvironment env = new ExecutionEnvironment();
        env.setId(envId);
        when(executionEnvironmentRepository.findById(envId)).thenReturn(Optional.of(env));

        RepoCredential credential = new RepoCredential(new Team(), "cred", CredentialType.PAT, "encrypted");
        SandboxExecution execution = executionWith(credential);
        when(sandboxExecutionRepository.findFirstByEnvironmentIdOrderByStartedAtDesc(envId))
                .thenReturn(Optional.of(execution));
        when(credentialResolver.resolve(credential)).thenReturn(GitAuthMaterial.none());

        assertThatThrownBy(() -> handler.handle("session-1", 1, new EnvironmentRpcPayload.GitToken())
                        .blockLast())
                .isInstanceOf(RpcErrorException.class);
    }
}
